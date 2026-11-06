param(
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$TargetsCsv = 'tests/results/local-multi-performance/targets.csv',
    [string]$SourceId = 'mixed-correctness'
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$targets = @(Import-Csv (Resolve-Path $TargetsCsv) | Select-Object -First 95)
if ($targets.Count -lt 95) {
    throw 'The mixed test requires at least 95 target rows.'
}

function Send-Usage([pscustomobject]$Target, [hashtable]$Payload) {
    $response = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/v1/ingest/usage-events" `
        -Headers @{'X-API-Key' = $Target.apiKey; Accept = 'application/json'} `
        -ContentType 'application/json' -Body ($Payload | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck -TimeoutSec 15
    $json = $null
    if ($response.Content) {
        $json = $response.Content | ConvertFrom-Json
    }
    [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $json }
}

$normal = [Collections.Generic.List[object]]::new()
for ($i = 0; $i -lt 95; $i++) {
    $target = $targets[$i]
    $payload = @{
        subscriptionId = [long]$target.subscriptionId
        sourceId = $SourceId
        eventId = "normal-$([guid]::NewGuid())"
        meterCode = $target.meterCode
        modelCode = 'PERF-MODEL'
        quantity = 1
        occurredAt = [DateTimeOffset]::UtcNow.AddSeconds(-3).ToString('O')
    }
    $response = Send-Usage $target $payload
    if ($response.Status -ne 202 -or $response.Json.code -ne 'SUCCESS' -or
        $response.Json.data.eventId -ne $payload.eventId -or
        $response.Json.data.sourceId -ne $SourceId) {
        throw "Normal request $i failed: HTTP $($response.Status)"
    }
    $normal.Add([pscustomobject]@{ Target = $target; Payload = $payload; Response = $response })
}

$duplicateAccepted = 0
for ($i = 0; $i -lt 3; $i++) {
    $original = $normal[$i]
    $response = Send-Usage $original.Target $original.Payload
    if ($response.Status -ne 202 -or $response.Json.code -ne 'SUCCESS' -or
        $response.Json.data.id -ne $original.Response.Json.data.id) {
        throw "Idempotent duplicate $i did not return the original event."
    }
    $duplicateAccepted++
}

$conflictPayload = @{} + $normal[3].Payload
$conflictPayload.quantity = 2
$conflict = Send-Usage $normal[3].Target $conflictPayload
if ($conflict.Status -ne 409) {
    throw "Conflicting duplicate returned HTTP $($conflict.Status), expected 409."
}

$invalidPayload = @{} + $normal[4].Payload
$invalidPayload.eventId = "invalid-$([guid]::NewGuid())"
$invalidPayload.subscriptionId = 999999999
$invalid = Send-Usage $normal[4].Target $invalidPayload
if ($invalid.Status -lt 400 -or $invalid.Status -ge 500) {
    throw "Invalid subscription returned HTTP $($invalid.Status), expected a 4xx response."
}

$result = [ordered]@{
    sent = 100
    normalAccepted = $normal.Count
    idempotentDuplicates = $duplicateAccepted
    expectedConflicts = 1
    expectedRejections = 1
    conflictHttpStatus = $conflict.Status
    rejectionHttpStatus = $invalid.Status
}
$result | ConvertTo-Json
