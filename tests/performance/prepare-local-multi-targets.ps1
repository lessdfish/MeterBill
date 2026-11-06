param(
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$Output = 'tests/results/local-multi-performance/targets.csv',
    [string]$Email = 'perf-admin@meterbill.local',
    [string]$Password = 'MeterBill-Perf-2026!'
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')

$loginBody = @{ email = $Email; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body $loginBody
if ($login.code -ne 'SUCCESS' -or -not $login.data.accessToken) {
    throw 'Performance administrator login failed.'
}

$outputPath = [IO.Path]::GetFullPath((Join-Path (Get-Location) $Output))
$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$tenantKeys = @{}
for ($tenantIndex = 1; $tenantIndex -le 100; $tenantIndex++) {
    $tenantId = 910000 + $tenantIndex
    $headers = @{
        Authorization = "Bearer $($login.data.accessToken)"
        'X-Tenant-Id' = "$tenantId"
    }
    $keyBody = @{
        name = "local-performance-$tenantIndex"
        expiresInDays = 1
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/tenant/api-keys" -Headers $headers -ContentType 'application/json' -Body $keyBody
    if ($created.code -ne 'SUCCESS' -or -not $created.data.apiKey) {
        throw "API key creation failed for tenant $tenantId."
    }
    $tenantKeys[$tenantIndex] = $created.data.apiKey
}

$rows = [Collections.Generic.List[object]]::new()
for ($tenantIndex = 1; $tenantIndex -le 100; $tenantIndex++) {
    $weight = if ($tenantIndex -le 20) { 16 } else { 1 }
    for ($slot = 0; $slot -lt $weight; $slot++) {
        $subscriptionOffset = ($tenantIndex - 1) * 5 + ($slot % 5) + 1
        $rows.Add([pscustomobject]@{
            apiKey = $tenantKeys[$tenantIndex]
            subscriptionId = 920000 + $subscriptionOffset
            meterCode = if (($slot % 2) -eq 0) { 'INPUT_TOKENS' } else { 'OUTPUT_TOKENS' }
        })
    }
}

$rows | Export-Csv -Path $outputPath -NoTypeInformation -Encoding utf8
[pscustomobject]@{
    output = $outputPath
    tenants = $tenantKeys.Count
    subscriptions = 500
    weightedRows = $rows.Count
    hotTenantRows = 320
    coldTenantRows = 80
} | ConvertTo-Json
