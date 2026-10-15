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
try{
 # Reproduce stale M02 failure against current running API, in a new draft only.
 $vp="/api/v1/products/$prod/plans/$plan/prices/$($state.price)/versions"
 $draft=Api 'M02 new draft for rule replacement' POST $vp $admin @{baseAmountMinor=6000}
 $r=Api 'M02 draft initial tiers' PUT "$vp/$draft/usage-rules/$meter" $admin @{mode='GRADUATED';includedQuantity=10;tiers=@(@{upToQuantity=10;unitAmountMinor=2},@{unitAmountMinor=1});revision=0}
 $null=Api 'M02 replace complete tier rule' PUT "$vp/$draft/usage-rules/$meter" $admin @{mode='PER_UNIT';includedQuantity=20;unitAmountMinor=3;tiers=@();revision=1} @(200,500)
 $row=$http[$http.Count-1]; Check 'M02 rule replacement works' ($row.status -eq 200) "HTTP $($row.status): $($row.body)"
 # Concurrent HTTP issue calls against the same ended period.
 $issueUrl="$BaseUrl/api/v1/platform/billing/periods/$($state.period)/issue"
 $results=1..8|ForEach-Object -Parallel { $r=Invoke-WebRequest -Uri $using:issueUrl -Method Post -Headers $using:admin -SkipHttpErrorCheck;[pscustomobject]@{status=[int]$r.StatusCode;body=$r.Content} } -ThrottleLimit 8
 $results|ConvertTo-Json -Depth 8|Set-Content "$out/concurrent-issue.json"
 $ids=@($results|ForEach-Object {($_.body|ConvertFrom-Json).data.id}|Select-Object -Unique)
 Check 'M05 eight concurrent issue requests' (@($results|Where-Object status -ne 200).Count -eq 0 -and $ids.Count -eq 1) "statuses=$($results.status -join ','); invoice IDs=$($ids -join ',') (existing invoice replay)"
 # Concurrent refund reservations: remaining 4035, each asks 3000.
 $refundUrl="$BaseUrl/api/v1/tenant/payments/$($state.payment)/refunds"
 $rs=1..2|ForEach-Object -Parallel { $h=@{};foreach($k in $using:hA.Keys){$h[$k]=($using:hA)[$k]};$h['Idempotency-Key']="$using:run-concurrent-$_"; $r=Invoke-WebRequest $using:refundUrl -Method Post -Headers $h -ContentType application/json -Body '{"amountMinor":3000}' -SkipHttpErrorCheck;[pscustomobject]@{status=[int]$r.StatusCode;body=$r.Content} } -ThrottleLimit 2
 $rs|ConvertTo-Json -Depth 8|Set-Content "$out/concurrent-refund.json"
 Check 'M06 concurrent refund limit' (@($rs|Where-Object status -eq 200).Count -eq 1 -and @($rs|Where-Object status -eq 409).Count -eq 1) "statuses=$($rs.status -join ',')"
 # Warm cache, invalidate only this fixture key, reload.
 $at=[DateTime]::UtcNow.AddSeconds(-1).ToString('o')
 $ev=Api 'M08 warm meter cache' POST /api/v1/ingest/usage-events $kh (Event 'cache-warm' 1 $at) @(202);WaitEvent $ev.id
 $cacheKey="meterbill:usage-meter:${prod}:SU5QVVRfVE9LRU5T"
 $ttl=Redis "TTL $cacheKey";Check 'M08 cache TTL' ([int]$ttl -gt 0 -and [int]$ttl -le 600) "TTL=$ttl seconds"
 $null=Redis "DEL $cacheKey"
 $ev=Api 'M08 cache miss reload' POST /api/v1/ingest/usage-events $kh (Event 'cache-reload' 1 $at) @(202);WaitEvent $ev.id
 Check 'M08 correct cached mapping' ((Redis "GET $cacheKey") -eq $meter) "meter=$meter"
 # Short reversible Redis outage; restore even if request fails.
 try{
  docker stop -t 2 meterbill-redis|Out-Null
  $ev=Api 'M08 Redis outage DB fallback' POST /api/v1/ingest/usage-events $kh (Event 'redis-offline' 1 $at) @(202);WaitEvent $ev.id
 }finally{docker start meterbill-redis|Out-Null}
 # Broker outage proves durable acceptance and backlog, then actual listener reconnect.
 try{
  docker stop -t 3 meterbill-rabbitmq|Out-Null
  $ev=Api 'M04 Rabbit offline durable accept' POST /api/v1/ingest/usage-events $kh (Event 'rabbit-offline' 7 $at) @(202)
  Check 'M04 offline event and outbox persisted' ((Sql "SELECT COUNT(*) FROM mb_usage_event e JOIN mb_outbox o ON o.event_id=e.id WHERE e.id=$($ev.id);") -eq 1) "event=$($ev.id)"
 }finally{docker start meterbill-rabbitmq|Out-Null}
 for($i=0;$i -lt 60;$i++){if((Sql "SELECT status FROM mb_usage_event WHERE id=$($ev.id);") -eq 'PROCESSED'){break};Start-Sleep -Seconds 1}
 Check 'M04 broker restart backlog recovered' ((Sql "SELECT status FROM mb_usage_event WHERE id=$($ev.id);") -eq 'PROCESSED') "event=$($ev.id)"
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

