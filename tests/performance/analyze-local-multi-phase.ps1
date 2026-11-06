param(
    [Parameter(Mandatory)][string]$ResultsJtl,
    [Parameter(Mandatory)][ValidateRange(0, 3600)][int]$WarmupSeconds,
    [Parameter(Mandatory)][ValidateRange(1, 3600)][int]$SteadySeconds
)

$ErrorActionPreference = 'Stop'
$rows = @(Import-Csv (Resolve-Path $ResultsJtl))
if ($rows.Count -eq 0) {
    throw "No samples found in $ResultsJtl"
}

$timestamps = @($rows | ForEach-Object { $_.timeStamp -as [long] })
$firstTimestamp = ($timestamps | Measure-Object -Minimum).Minimum -as [long]
$lastTimestamp = ($timestamps | Measure-Object -Maximum).Maximum -as [long]
$observedSeconds = ($lastTimestamp - $firstTimestamp + 1) / 1000
if ($observedSeconds -lt $SteadySeconds) {
    throw "Observed sample span is only $observedSeconds seconds."
}

# JMeter ramps threads before the first request is emitted. For a run scheduled as
# warm-up + steady time, the final contiguous SteadySeconds are the unambiguous
# steady-state window; anchoring on the first sample would include part of ramp-up.
$windowEnd = $lastTimestamp + 1
$windowStart = $windowEnd - ($SteadySeconds * 1000L)
$steadyRows = @($rows | Where-Object {
    $timestamp = $_.timeStamp -as [long]
    $timestamp -ge $windowStart -and $timestamp -lt $windowEnd
})
if ($steadyRows.Count -eq 0) {
    throw 'No samples found in the requested steady-state window.'
}

$elapsed = @($steadyRows | ForEach-Object { $_.elapsed -as [int] } | Sort-Object)
function Get-NearestRankPercentile([int[]]$Values, [double]$Percentile) {
    $index = [math]::Ceiling($Percentile * $Values.Count) - 1
    return $Values[[math]::Max(0, $index)]
}

$errors = @($steadyRows | Where-Object { $_.success -ne 'true' }).Count
$result = [ordered]@{
    resultsJtl = (Resolve-Path $ResultsJtl).Path
    warmupSeconds = $WarmupSeconds
    steadySeconds = $SteadySeconds
    observedSeconds = [math]::Round($observedSeconds, 2)
    sampleCount = $steadyRows.Count
    errors = $errors
    achievedPerSecond = [math]::Round($steadyRows.Count / $SteadySeconds, 2)
    averageMs = [math]::Round(($elapsed | Measure-Object -Average).Average, 2)
    p50Ms = Get-NearestRankPercentile $elapsed 0.50
    p90Ms = Get-NearestRankPercentile $elapsed 0.90
    p95Ms = Get-NearestRankPercentile $elapsed 0.95
    p99Ms = Get-NearestRankPercentile $elapsed 0.99
    minMs = $elapsed[0]
    maxMs = $elapsed[-1]
}

$outputPath = Join-Path (Split-Path (Resolve-Path $ResultsJtl)) 'steady-summary.json'
$result | ConvertTo-Json | Set-Content $outputPath -Encoding utf8
$result | ConvertTo-Json
if ($errors -ne 0) {
    throw "$errors requests failed in the steady-state window."
}
