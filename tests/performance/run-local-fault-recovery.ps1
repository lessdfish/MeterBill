param(
    [string]$Project = 'meterbill-local-perf',
    [string]$EnvFile = 'deploy/.env.local-perf',
    [int]$GatewayPort = 18081,
    [string]$TargetsCsv = 'tests/results/local-multi-performance/targets.csv',
    [ValidateRange(1, 100)][int]$RabbitOutageEvents = 10
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$compose = @('compose', '-p', $Project, '--env-file', $EnvFile, '-f', 'deploy/compose.yaml')

function Invoke-Compose([string[]]$Arguments) {
    & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Wait-ServiceHealthy([string]$Service, [int]$TimeoutSeconds = 120) {
    $deadline = [DateTimeOffset]::Now.AddSeconds($TimeoutSeconds)
    do {
        $containerId = (& docker @compose ps -q $Service).Trim()
        if ($containerId) {
            $health = (& docker inspect $containerId --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}').Trim()
            if ($health -eq 'healthy') { return }
        }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::Now -lt $deadline)
    throw "$Service did not become healthy within $TimeoutSeconds seconds."
}

function Assert-GatewayAvailable([int]$Attempts = 10) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$GatewayPort/actuator/health/readiness" -SkipHttpErrorCheck -TimeoutSec 10
        if ($response.StatusCode -ne 200) { throw "Gateway readiness returned HTTP $($response.StatusCode) on attempt $($i + 1)." }
    }
}

$failover = [ordered]@{}
foreach ($service in @('app1', 'app2')) {
    try {
        Invoke-Compose @('stop', $service)
        Assert-GatewayAvailable 10
        $failover[$service] = '10/10 readiness requests succeeded while stopped'
    } finally {
        Invoke-Compose @('start', $service)
        Wait-ServiceHealthy $service
    }
}

$targets = @(Import-Csv (Resolve-Path $TargetsCsv) | Select-Object -First $RabbitOutageEvents)
if ($targets.Count -ne $RabbitOutageEvents) { throw "Targets CSV contains fewer than $RabbitOutageEvents rows." }
$sourceId = "rabbit-outage-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$accepted = [Collections.Generic.List[object]]::new()
try {
    Invoke-Compose @('stop', 'rabbitmq')
    foreach ($target in $targets) {
        $eventId = [guid]::NewGuid().ToString()
        $payload = @{
            subscriptionId = [long]$target.subscriptionId
            sourceId = $sourceId
            eventId = $eventId
            meterCode = $target.meterCode
            modelCode = 'PERF-MODEL'
            quantity = 1
            occurredAt = [DateTimeOffset]::UtcNow.AddSeconds(-3).ToString('O')
        }
        $response = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:$GatewayPort/api/v1/ingest/usage-events" `
            -Headers @{'X-API-Key' = $target.apiKey; Accept = 'application/json'} -ContentType 'application/json' `
            -Body ($payload | ConvertTo-Json -Compress) -SkipHttpErrorCheck -TimeoutSec 15
        $json = $response.Content | ConvertFrom-Json
        if ($response.StatusCode -ne 202 -or $json.code -ne 'SUCCESS') {
            throw "Usage event was not durably accepted while RabbitMQ was stopped: HTTP $($response.StatusCode)."
        }
        $accepted.Add([pscustomobject]@{ Target = $target; EventId = $eventId })
    }
} finally {
    Invoke-Compose @('start', 'rabbitmq')
    Wait-ServiceHealthy 'rabbitmq'
}

$deadline = [DateTimeOffset]::Now.AddMinutes(3)
$remaining = @($accepted)
do {
    $next = [Collections.Generic.List[object]]::new()
    foreach ($item in $remaining) {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$GatewayPort/api/v1/ingest/usage-events/$sourceId/$($item.EventId)" `
            -Headers @{'X-API-Key' = $item.Target.apiKey; Accept = 'application/json'} -SkipHttpErrorCheck -TimeoutSec 10
        $json = $response.Content | ConvertFrom-Json
        if ($response.StatusCode -ne 200 -or $json.data.status -ne 'PROCESSED') { $next.Add($item) }
    }
    $remaining = @($next)
    if ($remaining.Count -gt 0) { Start-Sleep -Seconds 2 }
} while ($remaining.Count -gt 0 -and [DateTimeOffset]::Now -lt $deadline)

if ($remaining.Count -gt 0) { throw "$($remaining.Count) RabbitMQ-outage events were not processed after recovery." }

[ordered]@{
    app1Failover = $failover.app1
    app2Failover = $failover.app2
    rabbitOutageAccepted = $accepted.Count
    rabbitOutageProcessedAfterRecovery = $accepted.Count
} | ConvertTo-Json
