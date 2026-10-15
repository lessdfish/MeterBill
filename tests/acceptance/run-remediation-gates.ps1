param([Parameter(Mandatory)][string]$Run,[string]$BaseUrl='http://localhost:8085')
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
 $vp="/api/v1/products/$prod/plans/$plan/prices/$($state.price)/versions"
 $draft=Api 'A02 draft' POST $vp $admin @{baseAmountMinor=5000}
 $revision=0
 foreach($mode in @('PER_UNIT','GRADUATED','VOLUME','PER_UNIT')){
  $rule=@{mode=$mode;includedQuantity=0;revision=$revision;tiers=@()}
  if($mode -eq 'PER_UNIT'){$rule.unitAmountMinor=3}else{$rule.tiers=@(@{upToQuantity=10;unitAmountMinor=2},@{unitAmountMinor=1})}
  $r=Api "A02 replace $mode revision $revision" PUT "$vp/$draft/usage-rules/$meter" $admin $rule
  Check "A02 $mode persisted" ($r.mode -eq $mode -and ($mode -eq 'PER_UNIT' -or $null -eq $r.unitAmountMinor)) ($r|ConvertTo-Json -Compress -Depth 8)
  $revision++
 }
 $cfg=@{};Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
 $uid=Sql "INSERT INTO mb_user(email,password_hash,display_name) SELECT '${run}_viewer@example.test',password_hash,'Fixed Viewer' FROM mb_user WHERE id=$($state.userA); SELECT LAST_INSERT_ID();"
 $mid=Sql "INSERT INTO mb_tenant_member(tenant_id,user_id) VALUES($tenantA,$uid); SELECT LAST_INSERT_ID();"
 $rid=Sql "INSERT INTO mb_role(tenant_id,role_code,name) VALUES($tenantA,'VIEWER','Viewer'); SELECT LAST_INSERT_ID();"
 $null=Sql "INSERT INTO mb_role_permission VALUES($rid,'TENANT_READ'); INSERT INTO mb_member_role VALUES($tenantA,$mid,$rid);"
 $l=Api 'A01 viewer login' POST /api/v1/auth/login @{} @{email="${run}_viewer@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
 $hv=@{Authorization="Bearer $($l.accessToken)";'X-Tenant-Id'="$tenantA"}
 $routes=@('/api/v1/tenant/subscriptions',"/api/v1/tenant/subscriptions/$($state.subscription)/activate","/api/v1/tenant/subscriptions/$($state.subscription)/cancel","/api/v1/tenant/subscriptions/$($state.subscription)/renew","/api/v1/tenant/subscriptions/$($state.subscription)/plan-changes","/api/v1/tenant/invoices/$($state.invoice)/payments","/api/v1/tenant/payments/$($state.payment)/refunds",'/api/v1/tenant/reconciliation/runs')
 foreach($route in $routes){$null=Api "A01 viewer denied $route" POST $route $hv @{planId=$plan;amountMinor=1} @(403) "$run-viewer"}
 # New invoice competition, not just replaying an existing invoice.
 $race=Api 'M07 create competing invoice fixture' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-race"
 $race=Api 'M07 activate competing invoice fixture' POST "/api/v1/tenant/subscriptions/$($race.id)/activate" $hA
 $period=Sql "SELECT id FROM mb_subscription_period WHERE subscription_id=$($race.id);"
 $null=Sql "UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH,status='ENDED' WHERE id=$period;"
 $rs=1..12|ForEach-Object -Parallel {$port=if($_%2){8085}else{8086};$w=Invoke-WebRequest "http://localhost:$port/api/v1/platform/billing/periods/$using:period/issue" -Method Post -Headers $using:admin -SkipHttpErrorCheck;[pscustomobject]@{port=$port;status=[int]$w.StatusCode;body=$w.Content}} -ThrottleLimit 12
 $rs|ConvertTo-Json -Depth 8|Set-Content "$out/fixed-two-instance.json"
 $ids=@($rs|ForEach-Object {($_.body|ConvertFrom-Json).data.id}|Select-Object -Unique)
 Check 'M07 two fixed instances one invoice one posting' (@($rs|Where-Object status -ne 200).Count -eq 0 -and $ids.Count -eq 1 -and (Sql "SELECT COUNT(*) FROM mb_ledger_transaction WHERE tenant_id=$tenantA AND business_type='INVOICE' AND business_id=$($ids[0]);") -eq 1) "status=$($rs.status -join ',');ids=$($ids -join ',')"
 $rec=Api 'A03 unpaid concurrent invoice reconciles' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-before-load"
 Check 'A03/A04 zero differences before load' ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
 # Real bounded offered-load steps and a sustained phase; backlog recorded after each.
 $stats=[System.Collections.Generic.List[object]]::new()
 foreach($phase in @(@{name='step25';rate=25;seconds=45},@{name='step50';rate=50;seconds=45},@{name='step100';rate=100;seconds=45},@{name='sustain25';rate=25;seconds=300})){
  $phaseOut=Join-Path $out $phase.name;New-Item -ItemType Directory -Force $phaseOut|Out-Null
  $props=Join-Path $env:TEMP "$run-performance.properties"
  @("API_KEY=$($kh['X-API-Key'])","SUBSCRIPTION_ID=$($state.perfSubscription)",'METER_CODE=INPUT_TOKENS','THREADS=20','RAMP_UP_SECONDS=5',"DURATION_SECONDS=$($phase.seconds)","TARGET_PER_MINUTE=$($phase.rate*60)","SOURCE_ID=$($phase.name)")|Set-Content $props
  try{
   & 'E:/Downloads/apache-jmeter-5.6.3/bin/jmeter.bat' -n -t tests/jmeter/m08-bounded-capacity.jmx -q $props -l "$phaseOut/results.jtl" -j "$phaseOut/jmeter.log" -e -o "$phaseOut/html"
   Check "M08 $($phase.name) JMeter exit" ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
  }finally{Remove-Item -LiteralPath $props -ErrorAction SilentlyContinue}
  $s=(Get-Content "$phaseOut/html/statistics.json" -Raw|ConvertFrom-Json).Total
  $pending=Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';"
  $count=Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND source_id='$($phase.name)';"
  $item=[pscustomobject]@{phase=$phase.name;offeredPerSec=$phase.rate;duration=$phase.seconds;requests=$s.sampleCount;errors=$s.errorCount;throughput=$s.throughput;p95=$s.pct2ResTime;p99=$s.pct3ResTime;pendingAtEnd=[int]$pending;dbEvents=[int]$count}
  $stats.Add($item);$stats|ConvertTo-Json -Depth 8|Set-Content "$out/fixed-performance.json"
  Check "M08 $($phase.name) accepted count and errors" ($s.errorCount -eq 0 -and [int]$count -eq $s.sampleCount) ($item|ConvertTo-Json -Compress)
 }
 for($i=0;$i -lt 300;$i++){
  $pending=Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';"
  if($pending -eq 0){break};if($i%15 -eq 0){Write-Host "draining pending=$pending"};Start-Sleep -Seconds 2
 }
 Check 'M08 final drained' ($pending -eq 0) "pending=$pending"
 $bad=Sql "SELECT COUNT(*) FROM (SELECT e.period_id,e.meter_id,SUM(e.quantity) q,MAX(t.total_quantity) t FROM mb_usage_event e LEFT JOIN mb_usage_period_total t ON t.period_id=e.period_id AND t.meter_id=e.meter_id WHERE e.tenant_id=$tenantA AND e.status='PROCESSED' GROUP BY e.period_id,e.meter_id HAVING q<>t OR t IS NULL) x;"
 Check 'INV02 all usage aggregates exact' ($bad -eq 0) "mismatches=$bad"
 # Close only our performance fixture so loaded events are actually billed and reconciled.
 $perfPeriod=Sql "SELECT id FROM mb_subscription_period WHERE subscription_id=$($state.perfSubscription) AND status='ACTIVE';"
 $qty=[long](Sql "SELECT SUM(quantity) FROM mb_usage_event WHERE period_id=$perfPeriod;")
 $null=Sql "UPDATE mb_usage_event SET occurred_at=DATE_SUB(occurred_at,INTERVAL 1 MONTH) WHERE period_id=$perfPeriod; UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH,status='ENDED' WHERE id=$perfPeriod;"
 $invoice=Api 'M08 issue loaded events' POST "/api/v1/platform/billing/periods/$perfPeriod/issue" $admin
 $expected=5000+[math]::Min([math]::Max(0,$qty-100),10)*2+[math]::Max(0,$qty-110)
 Check 'INV04 loaded deterministic invoice' ($invoice.totalAmountMinor -eq $expected) "quantity=$qty total=$($invoice.totalAmountMinor) expected=$expected"
 $rec=Api 'M08 post-load reconciliation' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-after-load"
 $rec|ConvertTo-Json -Depth 15|Set-Content "$out/fixed-post-load-reconciliation.json"
 Check 'M08 financial invariants post-load' ($rec.differences.Count -eq 0) "differences=$($rec.differences.Count)"
 Check 'INV05 all ledger entries balance' ((Sql "SELECT COUNT(*) FROM (SELECT t.id FROM mb_ledger_transaction t JOIN mb_ledger_entry e ON e.transaction_id=t.id WHERE t.tenant_id=$tenantA GROUP BY t.id HAVING SUM(IF(e.direction='DEBIT',e.amount_minor,-e.amount_minor))<>0) x;") -eq 0) 'unbalanced=0'
 $null=Api 'M01 revoke A' POST "/api/v1/tenant/api-keys/$($state.keyId)/revoke" $hA
 $null=Api 'M01 revoked key cannot ingest warmed cache' POST /api/v1/ingest/usage-events $kh (Event 'denied' 1 ([DateTime]::UtcNow.ToString('o'))) @(401)
 $null=Api 'M01 revoke B' POST "/api/v1/tenant/api-keys/$($state.keyIdB)/revoke" $hB
 $null=Sql "UPDATE mb_user SET status='DISABLED' WHERE email LIKE '${run}%@example.test';"
 Write-Host "REACCEPTANCE_RESULT=$out"
}finally{$checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"}
