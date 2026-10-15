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
 $vp="/api/v1/products/$prod/plans/$plan/prices/$($state.price)/versions"
 $draft=Sql "SELECT MAX(id) FROM mb_price_version WHERE price_id=$($state.price) AND status='DRAFT';"
 $r=Api 'M02 PER_UNIT to GRADUATED replacement' PUT "$vp/$draft/usage-rules/$meter" $admin @{mode='GRADUATED';includedQuantity=20;tiers=@(@{upToQuantity=10;unitAmountMinor=2},@{unitAmountMinor=1});revision=2} @(200,500)
 $row=$http[$http.Count-1];Check 'M02 nullable unit price clears on mode change' ($row.status -eq 200) "HTTP $($row.status), $($row.body)"
 $invalid=Api 'M02 invalid tier bounds' PUT "$vp/$draft/usage-rules/$meter" $admin @{mode='GRADUATED';includedQuantity=20;tiers=@(@{upToQuantity=10;unitAmountMinor=2},@{upToQuantity=5;unitAmountMinor=1});revision=2} @(400)
 # Publish a new valid future-effective version to verify existing period snapshot.
 $v2=$draft
 $null=Api 'M05 update changed price draft' PUT "$vp/$v2" $admin @{baseAmountMinor=9000;revision=2}
 $null=Api 'M05 publish changed price' POST "$vp/$v2/publish" $admin @{effectiveAt=[DateTime]::UtcNow.AddSeconds(2).ToString('o');revision=3}
 Start-Sleep -Seconds 3
 $inv=Api 'M05 read historical invoice after price change' GET "/api/v1/tenant/invoices/$($state.invoice)" $hA
 Check 'M05 changed price preserves old snapshot' ($inv.totalAmountMinor -eq 5035 -and $inv.priceVersionId -eq $state.version) "invoice=$($inv.id) amount=$($inv.totalAmountMinor) version=$($inv.priceVersionId)"
 $new=Api 'M03 cancellation fixture' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-cancel"
 $new=Api 'M03 activate cancellation fixture' POST "/api/v1/tenant/subscriptions/$($new.id)/activate" $hA
 $c=Api 'M03 immediate cancel' POST "/api/v1/tenant/subscriptions/$($new.id)/cancel?immediate=true" $hA
 Check 'M03 canceled terminal state' ($c.status -eq 'CANCELED') $c.status
 $null=Api 'M03 cannot reactivate canceled' POST "/api/v1/tenant/subscriptions/$($new.id)/activate" $hA $null @(409)
 $expiry=Api 'M03 expiry fixture' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-expire"
 $expiry=Api 'M03 activate expiry fixture' POST "/api/v1/tenant/subscriptions/$($expiry.id)/activate" $hA
 $expiry=Api 'M03 schedule cancellation' POST "/api/v1/tenant/subscriptions/$($expiry.id)/cancel" $hA
 Check 'M03 cancellation scheduled' ($expiry.status -eq 'CANCEL_SCHEDULED') $expiry.status
 $null=Sql "UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH WHERE subscription_id=$($expiry.id);"
 $expiry=Api 'M03 expiry at boundary' POST "/api/v1/tenant/subscriptions/$($expiry.id)/renew" $hA
 Check 'M03 expired terminal state' ($expiry.status -eq 'EXPIRED') $expiry.status
 # Add a VIEWER member to A, check explicit role permission boundary and business mutation.
 $viewer=Sql "INSERT INTO mb_user(email,password_hash,display_name) SELECT '${run}_viewer@example.test',password_hash,'Acceptance viewer' FROM mb_user WHERE id=$($state.userA); SELECT LAST_INSERT_ID();"
 $member=Sql "INSERT INTO mb_tenant_member(tenant_id,user_id) VALUES($tenantA,$viewer); SELECT LAST_INSERT_ID();"
 $role=Sql "INSERT INTO mb_role(tenant_id,role_code,name) VALUES($tenantA,'VIEWER','Acceptance viewer'); SELECT LAST_INSERT_ID();"
 $null=Sql "INSERT INTO mb_role_permission VALUES($role,'TENANT_READ'); INSERT INTO mb_member_role VALUES($tenantA,$member,$role);"
 $cfg=@{};Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
 $l=Api 'M01 viewer login' POST /api/v1/auth/login @{} @{email="${run}_viewer@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
 $hv=@{Authorization="Bearer $($l.accessToken)";'X-Tenant-Id'="$tenantA"}
 $null=Api 'M01 viewer key issuance denied' POST /api/v1/tenant/api-keys $hv @{name='denied';expiresInDays=1} @(403)
 $viewerMutation=Api 'M01 viewer subscription write boundary' POST /api/v1/tenant/subscriptions $hv @{planId=$plan} @(200,403) "$run-viewer-write"
 Check 'M01 VIEWER cannot create subscription' ($http[$http.Count-1].status -eq 403) "HTTP $($http[$http.Count-1].status), subscription=$($viewerMutation.id)"
 # New invoice race on both live processes, with Redis unavailable (DB final barrier).
 $raceSub=Sql "INSERT INTO mb_subscription(tenant_id,plan_id,idempotency_key,request_hash,status) VALUES($tenantA,$plan,'$run-race',REPEAT('0',64),'ACTIVE'); SELECT LAST_INSERT_ID();"
 $racePeriod=Sql "INSERT INTO mb_subscription_period(tenant_id,subscription_id,plan_id,price_id,price_version_id,currency,base_amount_minor,active_from,period_start,period_end,status) VALUES($tenantA,$raceSub,$plan,$($state.price),$($state.version),'CNY',5000,'2026-08-31 16:00:00','2026-08-31 16:00:00','2026-09-30 16:00:00','ENDED'); SELECT LAST_INSERT_ID();"
 $racingUrl="/api/v1/platform/billing/periods/$racePeriod/issue"
 try{
  docker stop -t 2 meterbill-redis|Out-Null
  $rs=1..12|ForEach-Object -Parallel {$port=if($_%2){8085}else{8086};$r=Invoke-WebRequest "http://localhost:$port$using:racingUrl" -Method Post -Headers $using:admin -SkipHttpErrorCheck;[pscustomobject]@{port=$port;status=[int]$r.StatusCode;body=$r.Content}} -ThrottleLimit 12
 }finally{docker start meterbill-redis|Out-Null}
 $rs|ConvertTo-Json -Depth 8|Set-Content "$out/two-instance-new-issue.json"
 $ids=@($rs|ForEach-Object {($_.body|ConvertFrom-Json).data.id}|Select-Object -Unique)
 $count=Sql "SELECT COUNT(*) FROM mb_invoice WHERE period_id=$racePeriod;"
 Check 'M07 two instances new invoice Redis offline' (@($rs|Where-Object status -ne 200).Count -eq 0 -and $ids.Count -eq 1 -and $count -eq 1) "statuses=$($rs.status -join ','); IDs=$($ids -join ','); DB invoices=$count"
 Check 'M00 repeat startup no duplicate migrations' ((Sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;') -eq 13) 'second instance started; Flyway rows remain 13'
 # SQL EXPLAIN of the actual cache-miss lookup.
 Sql "EXPLAIN ANALYZE SELECT id FROM mb_usage_meter WHERE product_id=$prod AND meter_code='INPUT_TOKENS';" | Set-Content "$out/explain-meter.txt"
 Check 'M08 meter lookup index evidence' ((Get-Content "$out/explain-meter.txt" -Raw) -match 'index|Index') (Get-Content "$out/explain-meter.txt" -Raw)
 # Snapshot runtime invariants and precise financial discrepancy without mutating evidence.
 $queries=@"
SELECT 'event_count',COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA;
SELECT 'pending_events',COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';
SELECT 'outbox_unpublished',COUNT(*) FROM mb_outbox o JOIN mb_usage_event e ON e.id=o.event_id WHERE e.tenant_id=$tenantA AND o.status<>'PUBLISHED';
SELECT 'unbalanced_entries',COUNT(*) FROM (SELECT t.id FROM mb_ledger_transaction t JOIN mb_ledger_entry e ON e.transaction_id=t.id WHERE t.tenant_id=$tenantA GROUP BY t.id HAVING SUM(IF(e.direction='DEBIT',e.amount_minor,-e.amount_minor))<>0) x;
SELECT 'refund_overdrawn',COUNT(*) FROM (SELECT p.id,p.amount_minor,SUM(r.amount_minor) reserved FROM mb_payment p JOIN mb_refund r ON r.payment_id=p.id AND r.status IN ('PENDING','UNKNOWN','SUCCEEDED') WHERE p.tenant_id=$tenantA GROUP BY p.id HAVING reserved>p.amount_minor) x;
SELECT 'invoice',id,total_amount_minor,status FROM mb_invoice WHERE id=$($state.invoice);
SELECT 'adjustment',id,amount_minor,status FROM mb_invoice_adjustment WHERE invoice_id=$($state.invoice);
SELECT 'ledger',business_type,business_id,debit_total_minor,credit_total_minor FROM mb_ledger_transaction WHERE tenant_id=$tenantA;
SELECT 'security_audit',COUNT(*) FROM mb_security_audit WHERE tenant_id=$tenantA;
"@
 $queries|Set-Content "$out/invariants.sql";Sql $queries|Set-Content "$out/invariants.tsv"
 Write-Host "FINAL_RESULT_PATH=$out"
}finally{$checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"}

