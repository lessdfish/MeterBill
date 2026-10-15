param([string]$Run='ACC_20261011_201407',[string]$BaseUrl='http://localhost:8085')
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$run=$Run;$out=Join-Path (Get-Location) "tests/results/$run"
$state=Get-Content "$out/state.json" -Raw|ConvertFrom-Json
$auth=Import-Clixml (Join-Path $env:TEMP "$run-auth.xml")
$admin=$auth.admin;$hA=$auth.hA;$hB=$auth.hB;$kh=$auth.kh
$tenantA=$state.tenantA;$tenantB=$state.tenantB;$prod=$state.product;$plan=$state.plan;$meter=$state.meter
$sub=@{id=$state.perfSubscription}
$checks=[System.Collections.Generic.List[object]]::new(); foreach($r in (Get-Content "$out/checks.json" -Raw|ConvertFrom-Json)){$checks.Add($r)}
$http=[System.Collections.Generic.List[object]]::new();foreach($r in (Get-Content "$out/http.json" -Raw|ConvertFrom-Json)){$http.Add($r)}
. "$PSScriptRoot/live-helpers.ps1"
function Redis($arguments){ $cmd='REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli '+$arguments; docker exec meterbill-redis sh -c $cmd }

$ev=@{id=(Sql "SELECT id FROM mb_usage_event WHERE tenant_id=$tenantA AND event_id='rabbit-offline';")}
try{
 # Retry own already processed event as a controlled final-failed Outbox fixture.
 $before=Sql "SELECT total_quantity FROM mb_usage_period_total WHERE period_id=(SELECT period_id FROM mb_usage_event WHERE id=$($ev.id));"
 $null=Sql "UPDATE mb_usage_event SET status='FINAL_FAILED' WHERE id=$($ev.id); UPDATE mb_outbox SET status='FINAL_FAILED',last_error='Acceptance injected final failure' WHERE event_id=$($ev.id);"
 $null=Api 'M07 final failure recovery endpoint' POST "/api/v1/platform/operations/usage-events/$($ev.id)/retry" $admin
 Start-Sleep -Seconds 3
 $after=Sql "SELECT total_quantity FROM mb_usage_period_total WHERE period_id=(SELECT period_id FROM mb_usage_event WHERE id=$($ev.id));"
 Check 'M07 replay does not double aggregate' ($before -eq $after) "before=$before after=$after"
 # Difference case transitions and re-opening an unresolved financial mismatch.
 $rec=Api 'M07 repeated difference detection' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-extra-rec"
 $diff=$rec.differences[0]
 foreach($status in @('INVESTIGATING','RESOLVED')){$null=Api "M07 case $status" PATCH "/api/v1/platform/reconciliation/differences/$($diff.id)" $admin @{status=$status;note='Acceptance transition test; original issue still reproduced'}}
 $rec=Api 'M07 reopen unresolved difference' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-reopen"
 Check 'M07 case reopened same ID' (@($rec.differences|Where-Object {$_.id -eq $diff.id -and $_.status -eq 'OPEN'}).Count -eq 1) "difference=$($diff.id)"
 # JMeter uses a temporary properties file so credentials never enter arguments/evidence.
 $props=Join-Path $env:TEMP "$run-jmeter.properties"
 @("API_KEY=$($kh['X-API-Key'])","SUBSCRIPTION_ID=$($state.perfSubscription)",'METER_CODE=INPUT_TOKENS','THREADS=10','RAMP_UP_SECONDS=5','DURATION_SECONDS=30')|Set-Content $props
 try{
  & 'E:/Downloads/apache-jmeter-5.6.3/bin/jmeter.bat' -n -t tests/jmeter/m08-usage-ingest.jmx -q $props -l "$out/jmeter.jtl" -j "$out/jmeter.log" -e -o "$out/jmeter-html"
  Check 'M08 JMeter command' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
 }finally{Remove-Item -LiteralPath $props -ErrorAction SilentlyContinue}
 for($i=0;$i -lt 60;$i++){if((Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';") -eq 0){break};Start-Sleep -Seconds 1}
 $samples=Import-Csv "$out/jmeter.jtl";$times=@($samples|ForEach-Object {[double]$_.elapsed}|Sort-Object)
 $start=($samples|ForEach-Object {[double]$_.timeStamp}|Measure-Object -Minimum).Minimum
 $end=($samples|ForEach-Object {[double]$_.timeStamp+[double]$_.elapsed}|Measure-Object -Maximum).Maximum
 $stats=[ordered]@{requests=$samples.Count;errors=@($samples|Where-Object success -ne 'true').Count;throughput=$samples.Count/(($end-$start)/1000);p95=$times[[math]::Ceiling($times.Count*.95)-1];p99=$times[[math]::Ceiling($times.Count*.99)-1];threads=10;rampUp=5;duration=30}
 $stats|ConvertTo-Json|Set-Content "$out/performance.json"
 Check 'M08 JMeter no request errors' ($stats.errors -eq 0) ($stats|ConvertTo-Json -Compress)
 $dbCount=Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND source_id='jmeter';"
 Check 'M08 JMeter accepted events persisted' ([int]$dbCount -eq $samples.Count-$stats.errors) "DB=$dbCount; successful HTTP=$($samples.Count-$stats.errors)"
 Check 'M08 all events drained' ((Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';") -eq 0) 'pending=0'
 Check 'M08 aggregate invariant' ((Sql "SELECT COUNT(*) FROM (SELECT e.period_id,e.meter_id,SUM(e.quantity) q,MAX(t.total_quantity) t FROM mb_usage_event e LEFT JOIN mb_usage_period_total t ON t.period_id=e.period_id AND t.meter_id=e.meter_id WHERE e.tenant_id=$tenantA AND e.status='PROCESSED' GROUP BY e.period_id,e.meter_id HAVING q<>t OR t IS NULL) x;") -eq 0) 'mismatched totals=0'
 Check 'M08 deduplication markers' ((Sql "SELECT COUNT(*) FROM mb_usage_event e LEFT JOIN mb_usage_event_processed p ON p.usage_event_id=e.id WHERE e.tenant_id=$tenantA AND e.status='PROCESSED' AND p.usage_event_id IS NULL;") -eq 0) 'missing processed markers=0'
 $null=Api 'M01 revoke key' POST "/api/v1/tenant/api-keys/$($state.keyIdB)/revoke" $hB
 $null=Api 'M01 revoked key rejected' GET /api/v1/ingest/auth-check @{'X-API-Key'=$auth.apiKeyB} $null @(401)
 $null=Api 'M08 warmed cache cannot bypass revoked key' POST /api/v1/ingest/usage-events @{'X-API-Key'=$auth.apiKeyB} (Event 'revoked' 1 $at) @(401)
 $checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"
 Write-Host "RESULT_PATH=$out"
}finally{
 $checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"
}

