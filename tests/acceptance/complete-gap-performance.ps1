param([Parameter(Mandatory)][string]$Run,[Parameter(Mandatory)][string]$Label,[string]$BaseUrl='http://localhost:8086')
$ErrorActionPreference='Stop';Set-Location (Join-Path $PSScriptRoot '../..')
$state=Get-Content "tests/results/$Run/state.json" -Raw|ConvertFrom-Json
$auth=Import-Clixml (Join-Path $env:TEMP "$Run-auth.xml")
$admin=$auth.admin;$hA=$auth.hA;$kh=$auth.kh;$tenant=$state.tenantA
$out=Join-Path (Get-Location) "tests/results/G01-G04-20261012/$Label";New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new();$http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$stats=[Collections.Generic.List[object]]::new();$samples=[Collections.Generic.List[object]]::new()
$jmeterHome='E:/Downloads/apache-jmeter-5.6.3'
$drain=[Diagnostics.Stopwatch]::StartNew()
do{$pending=[int](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenant AND status<>'PROCESSED';");if($pending -eq 0){break};Start-Sleep -Seconds 2}while($drain.Elapsed.TotalSeconds -lt 600)
Check 'final drain' ($pending -eq 0) "pending=$pending,seconds=$($drain.Elapsed.TotalSeconds)"
@{drainSeconds=$drain.Elapsed.TotalSeconds;pending=$pending}|ConvertTo-Json|Set-Content "$out/drain.json"
$period=Sql "SELECT id FROM mb_subscription_period WHERE subscription_id=$($state.perfSubscription) AND status='ACTIVE';"
$qty=[long](Sql "SELECT SUM(quantity) FROM mb_usage_event WHERE period_id=$period;")
Check 'exact aggregate' ((Sql "SELECT total_quantity FROM mb_usage_period_total WHERE period_id=$period;") -eq $qty) "expected=$qty"
Check 'processed markers' ((Sql "SELECT COUNT(*) FROM mb_usage_event e LEFT JOIN mb_usage_event_processed p ON p.usage_event_id=e.id WHERE e.tenant_id=$tenant AND p.usage_event_id IS NULL;") -eq 0) 'missing=0'
$null=Sql "UPDATE mb_usage_event SET occurred_at=DATE_SUB(occurred_at,INTERVAL 1 MONTH) WHERE period_id=$period; UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH,status='ENDED' WHERE id=$period;"
$invoice=Api 'loaded invoice' POST "/api/v1/platform/billing/periods/$period/issue" $admin
$expected=5000+[math]::Min([math]::Max(0,$qty-100),10)*2+[math]::Max(0,$qty-110)
Check 'exact loaded bill' ($invoice.totalAmountMinor -eq $expected) "actual=$($invoice.totalAmountMinor),expected=$expected"
$rec=Api 'post-load reconciliation' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) ([guid]::NewGuid().ToString())
Check 'post-load no differences' ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
if(@($checks|Where-Object status -eq FAIL).Count){throw 'Performance invariants failed'}
