param(
    [Parameter(Mandatory)][ValidateRange(1, 10000)][int]$RatePerSecond,
    [Parameter(Mandatory)][ValidateRange(1, 3600)][int]$DurationSeconds,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Label,
    [string]$TargetsCsv = 'tests/results/local-multi-performance/targets.csv',
    [string]$OutputRoot = 'tests/results/local-multi-performance',
    [int]$Threads = 200,
    [int]$RampUpSeconds = 10,
    [string]$JMeterHome = 'E:/Downloads/apache-jmeter-5.6.3',
    [string]$Java = 'F:/Java/java21/bin/java.exe'
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')

$targetsPath = (Resolve-Path $TargetsCsv).Path
$planPath = (Resolve-Path 'tests/jmeter/m08-multi-tenant-usage.jmx').Path
$phaseDirectory = [IO.Path]::GetFullPath((Join-Path (Get-Location) "$OutputRoot/$Label"))
if (Test-Path $phaseDirectory) {
    throw "Output already exists: $phaseDirectory"
}
New-Item -ItemType Directory -Path $phaseDirectory | Out-Null

function Quote-ProcessArgument([string]$Value) {
    return '"' + $Value.Replace('"', '\"') + '"'
}

$arguments = @(
    '-Xms256m', '-Xmx1024m',
    '-jar', (Quote-ProcessArgument (Join-Path $JMeterHome 'bin/ApacheJMeter.jar')),
    '-n', '-t', (Quote-ProcessArgument $planPath),
    (Quote-ProcessArgument "-JTARGETS_CSV=$targetsPath"),
    '-JTARGET_HOST=127.0.0.1', '-JTARGET_PORT=18081',
    "-JTHREADS=$Threads", "-JRAMP_UP_SECONDS=$RampUpSeconds",
    "-JDURATION_SECONDS=$DurationSeconds", "-JTARGET_PER_MINUTE=$($RatePerSecond * 60)",
    "-JSOURCE_ID=$Label",
    '-l', (Quote-ProcessArgument (Join-Path $phaseDirectory 'results.jtl')),
    '-j', (Quote-ProcessArgument (Join-Path $phaseDirectory 'jmeter.log')),
    '-e', '-o', (Quote-ProcessArgument (Join-Path $phaseDirectory 'html'))
)

$process = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory (Get-Location).Path `
    -WindowStyle Hidden -PassThru -Wait `
    -RedirectStandardOutput (Join-Path $phaseDirectory 'stdout.log') `
    -RedirectStandardError (Join-Path $phaseDirectory 'stderr.log')
if ($process.ExitCode -ne 0) {
    throw "JMeter exited with code $($process.ExitCode); see $phaseDirectory."
}

$statistics = Get-Content (Join-Path $phaseDirectory 'html/statistics.json') -Raw | ConvertFrom-Json
$total = $statistics.Total
$result = [ordered]@{
    label = $Label
    offeredRatePerSecond = $RatePerSecond
    durationSeconds = $DurationSeconds
    sampleCount = $total.sampleCount
    errors = $total.errorCount
    achievedPerSecond = [math]::Round($total.throughput, 2)
    averageMs = $total.meanResTime
    p90Ms = $total.pct1ResTime
    p95Ms = $total.pct2ResTime
    p99Ms = $total.pct3ResTime
    minMs = $total.minResTime
    maxMs = $total.maxResTime
}
$result | ConvertTo-Json | Set-Content (Join-Path $phaseDirectory 'summary.json') -Encoding utf8
$result | ConvertTo-Json
if ($total.errorCount -ne 0) {
    throw "$($total.errorCount) requests failed assertions."
}
