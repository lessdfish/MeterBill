param([string]$BaseUrl='http://localhost:8085')
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$run='ACC_'+(Get-Date -Format 'yyyyMMdd_HHmmss')
$out=Join-Path (Get-Location) "tests/results/$run"
New-Item -ItemType Directory -Force $out|Out-Null
$checks=[System.Collections.Generic.List[object]]::new()
$http=[System.Collections.Generic.List[object]]::new()
$cfg=@{}; Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
function Sql([string]$query){
 $res=$query | docker exec -i meterbill-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -B'
 if($LASTEXITCODE -ne 0){throw 'SQL failed'}
 return $res
}
function Check($name,$ok,$evidence){
 $s=if($ok){'PASS'}else{'FAIL'}
 $checks.Add([pscustomobject]@{name=$name;status=$s;evidence=$evidence})
 Write-Host "$s $name : $evidence"
 $checks|ConvertTo-Json -Depth 15|Set-Content "$out/checks.json"
}
function Api($name,$method,$path,$headers=@{},$body=$null,$expected=@(200),$key=''){
 $h=@{}; foreach($k in $headers.Keys){$h[$k]=$headers[$k]}; if($key){$h['Idempotency-Key']=$key}
 $args=@{Uri="$BaseUrl$path";Method=$method;Headers=$h;SkipHttpErrorCheck=$true;TimeoutSec=30}
 if($null -ne $body){$args.ContentType='application/json';$args.Body=$body|ConvertTo-Json -Depth 15 -Compress}
 $w=Invoke-WebRequest @args; $j=$null;try{$j=$w.Content|ConvertFrom-Json}catch{}
 $redacted=if($path -match '/login$|/api-keys$'){'[credential response omitted]'}else{$w.Content}
 $http.Add([pscustomobject]@{name=$name;method=$method;path=$path;status=[int]$w.StatusCode;body=$redacted})
 $http|ConvertTo-Json -Depth 20|Set-Content "$out/http.json"
 Check $name ($expected -contains [int]$w.StatusCode) "HTTP $($w.StatusCode), code=$($j.code)"
 if($w.StatusCode -ge 400 -and $expected -notcontains [int]$w.StatusCode){throw "$name failed: $redacted"}
 return $j.data
}
function WaitEvent($eventId){
 for($i=0;$i -lt 40;$i++){
  $s=Sql "SELECT status FROM mb_usage_event WHERE id=$eventId;"
  if($s -eq 'PROCESSED'){return};Start-Sleep -Milliseconds 500
 };throw "Event $eventId not processed: $s"
}
function Event($id,$qty,$at,$subId=$sub.id){return @{subscriptionId=$subId;sourceId=$run;eventId=$id;meterCode='INPUT_TOKENS';modelCode='ACCEPTANCE_MODEL';quantity=$qty;occurredAt=$at}}
try{
 $login=Api 'M01 login' POST /api/v1/auth/login @{} @{email=$cfg.METERBILL_BOOTSTRAP_EMAIL;password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
 $admin=@{Authorization="Bearer $($login.accessToken)"}
 $me=Api 'M01 profile' GET /api/v1/auth/me $admin
 $adminId=$me.userId
 $null=Api 'M00 ping' GET /api/v1/system/ping
 Check 'M00 health' ((Invoke-RestMethod "$BaseUrl/actuator/health").status -eq 'UP') 'Actuator UP'
 Check 'M00 migrations' ((Sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;') -eq 13) 'Flyway V1-V13'
 foreach($label in @('A','B')){
  $tid=Sql "INSERT INTO mb_tenant(tenant_code,name) VALUES('${run}_$label','Acceptance $label'); SELECT LAST_INSERT_ID();"
  $uid=Sql "INSERT INTO mb_user(email,password_hash,display_name) SELECT '${run}_$label@example.test',password_hash,'Acceptance $label' FROM mb_user WHERE id=$adminId; SELECT LAST_INSERT_ID();"
  $mid=Sql "INSERT INTO mb_tenant_member(tenant_id,user_id) VALUES($tid,$uid); SELECT LAST_INSERT_ID();"
  $rid=Sql "INSERT INTO mb_role(tenant_id,role_code,name) VALUES($tid,'OWNER','Acceptance owner'); SELECT LAST_INSERT_ID();"
  $null=Sql "INSERT INTO mb_role_permission(role_id,permission_code) VALUES($rid,'TENANT_READ'),($rid,'API_KEY_MANAGE'),($rid,'AUDIT_READ'),($rid,'MEMBER_ROLE_MANAGE'); INSERT INTO mb_member_role VALUES($tid,$mid,$rid);"
  $l=Api "M01 tenant $label login" POST /api/v1/auth/login @{} @{email="${run}_$label@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
  Set-Variable "tenant$label" $tid
  Set-Variable "user$label" $uid
  Set-Variable "h$label" @{Authorization="Bearer $($l.accessToken)";'X-Tenant-Id'="$tid"}
 }
 $null=Api 'M01 cross-tenant header A to B' GET /api/v1/tenant/current @{Authorization=$hA.Authorization;'X-Tenant-Id'="$tenantB"} $null @(403)
 $null=Api 'M01 cross-tenant header B to A' GET /api/v1/tenant/current @{Authorization=$hB.Authorization;'X-Tenant-Id'="$tenantA"} $null @(403)
 $null=Api 'M01 tenant cannot manage catalog' POST /api/v1/products $hA @{productCode="${run}_DENY";name='Denied'} @(403)
 $apiKey=Api 'M01 issue key A' POST /api/v1/tenant/api-keys $hA @{name=$run;expiresInDays=1}
 $kh=@{'X-API-Key'=$apiKey.apiKey}
 $apiKeyB=Api 'M01 issue key B' POST /api/v1/tenant/api-keys $hB @{name=$run;expiresInDays=1}
 $prod=Api 'M02 product' POST /api/v1/products $admin @{productCode=$run;name='Acceptance product'}
 $plan=Api 'M02 plan' POST "/api/v1/products/$prod/plans" $admin @{planCode="${run}_PLAN";name='Acceptance plan'}
 $null=Api 'M02 reject incomplete publication' POST "/api/v1/products/$prod/plans/$plan/publish" $admin $null @(409,400)
 $meter=Api 'M02 meter' POST "/api/v1/products/$prod/meters" $admin @{meterCode='INPUT_TOKENS';name='Input tokens';unit='TOKEN'}
 $price=Api 'M02 price' POST "/api/v1/products/$prod/plans/$plan/prices" $admin @{currency='CNY';billingPeriod='MONTHLY'}
 $vp="/api/v1/products/$prod/plans/$plan/prices/$price/versions"
 $ver=Api 'M02 version' POST $vp $admin @{baseAmountMinor=5000}
 $rule=@{mode='GRADUATED';includedQuantity=100;tiers=@(@{upToQuantity=10;unitAmountMinor=2},@{unitAmountMinor=1});revision=0}
 $null=Api 'M02 graduated rule' PUT "$vp/$ver/usage-rules/$meter" $admin $rule
 foreach($pair in @(@(100,0),@(110,20),@(125,35))){
  $preview=Api "M02 boundary $($pair[0])" POST "$vp/$ver/usage-rules/$meter/preview" $admin @{quantity=$pair[0]}
  Check "M02 amount $($pair[0])" ($preview.usageAmountMinor -eq $pair[1]) ($preview|ConvertTo-Json -Compress -Depth 10)
 }
 $null=Api 'M02 publish price' POST "$vp/$ver/publish" $admin @{effectiveAt=[DateTime]::UtcNow.AddSeconds(2).ToString('o');revision=1}
 Start-Sleep -Seconds 3
 $null=Api 'M02 immutable version' PUT "$vp/$ver" $admin @{baseAmountMinor=1;revision=2} @(409)
 $null=Api 'M02 publish plan' POST "/api/v1/products/$prod/plans/$plan/publish" $admin
 $null=Api 'M02 publish product' POST "/api/v1/products/$prod/publish" $admin
 $sub=Api 'M03 create subscription' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-sub"
 $replay=Api 'M03 repeat subscription' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-sub"
 Check 'M03 same subscription ID' ($sub.id -eq $replay.id) "id=$($sub.id)"
 $sub=Api 'M03 activate' POST "/api/v1/tenant/subscriptions/$($sub.id)/activate" $hA
 Check 'M03 natural month boundary' ($sub.status -eq 'ACTIVE' -and ([datetime]$sub.periodStart).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss') -eq '2026-09-30T16:00:00' -and ([datetime]$sub.periodEnd).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss') -eq '2026-10-31T16:00:00') ($sub|ConvertTo-Json -Compress)
 $null=Api 'M03 foreign subscription' GET "/api/v1/tenant/subscriptions/$($sub.id)" $hB $null @(404,403)
 $null=Api 'M03 early renewal rejected' POST "/api/v1/tenant/subscriptions/$($sub.id)/renew" $hA $null @(409)
 Start-Sleep -Seconds 2
 $at=[DateTime]::UtcNow.AddSeconds(-1).ToString('o')
 $body=Event 'one' 50 $at
 $ev=Api 'M04 accept event' POST /api/v1/ingest/usage-events $kh $body @(202)
 $rep=Api 'M04 duplicate event' POST /api/v1/ingest/usage-events $kh $body @(202)
 Check 'M04 same event ID' ($ev.id -eq $rep.id) "id=$($ev.id)"
 $null=Api 'M04 conflict payload' POST /api/v1/ingest/usage-events $kh (Event 'one' 51 $at) @(409)
 $null=Api 'M04 foreign ingest' POST /api/v1/ingest/usage-events @{'X-API-Key'=$apiKeyB.apiKey} $body @(409)
 $ev2=Api 'M04 out of order event' POST /api/v1/ingest/usage-events $kh (Event 'two' 75 ([DateTime]::Parse($at).AddMilliseconds(-200).ToUniversalTime().ToString('o'))) @(202)
 WaitEvent $ev.id; WaitEvent $ev2.id
 Check 'M04 aggregate exactly 125' ((Sql "SELECT total_quantity FROM mb_usage_period_total WHERE tenant_id=$tenantA;") -eq 125) 'DB total=125'
 $period=Sql "SELECT id FROM mb_subscription_period WHERE subscription_id=$($sub.id);"
 $null=Api 'M05 active period issue rejected' POST "/api/v1/platform/billing/periods/$period/issue" $admin $null @(409)
 # Fixture only: move this run's period and event timestamps into the previous natural month.
 $null=Sql "UPDATE mb_usage_event SET occurred_at=DATE_SUB(occurred_at,INTERVAL 1 MONTH) WHERE tenant_id=$tenantA; UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH,status='ENDED' WHERE id=$period;"
 $invoice=Api 'M05 issue invoice' POST "/api/v1/platform/billing/periods/$period/issue" $admin
 Check 'M05 deterministic 5035' ($invoice.totalAmountMinor -eq 5035) "total=$($invoice.totalAmountMinor)"
 $initialRec=Api 'M06 reconcile unpaid invoice' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-before-pay"
 Check 'M06 invoice issue posts AR immediately' ($initialRec.differences.Count -eq 0) ($initialRec|ConvertTo-Json -Compress -Depth 10)
 $repeat=Api 'M05 repeat issue' POST "/api/v1/platform/billing/periods/$period/issue" $admin
 Check 'M05 unique invoice' ($repeat.id -eq $invoice.id) "invoice=$($invoice.id)"
 $null=Api 'M05 foreign invoice' GET "/api/v1/tenant/invoices/$($invoice.id)" $hB $null @(404,403)
 $pay=Api 'M06 create payment' POST "/api/v1/tenant/invoices/$($invoice.id)/payments" $hA @{amountMinor=5035} @(200) "$run-pay"
 $replay=Api 'M06 repeat payment' POST "/api/v1/tenant/invoices/$($invoice.id)/payments" $hA @{amountMinor=5035} @(200) "$run-pay"
 Check 'M06 same payment ID' ($pay.id -eq $replay.id) "id=$($pay.id)"
 foreach($result in @('UNKNOWN','SUCCEEDED','SUCCEEDED')){$null=Api "M06 payment $result" POST "/api/v1/platform/payments/$($pay.id)/simulate-result" $admin @{result=$result}}
 $null=Api 'M06 reverse terminal rejected' POST "/api/v1/platform/payments/$($pay.id)/simulate-result" $admin @{result='FAILED'} @(409)
 $paid=Api 'M06 paid invoice' GET "/api/v1/tenant/invoices/$($invoice.id)" $hA
 Check 'M06 invoice PAID' ($paid.status -eq 'PAID') $paid.status
 $refund=Api 'M06 refund' POST "/api/v1/tenant/payments/$($pay.id)/refunds" $hA @{amountMinor=1000} @(200) "$run-refund"
 foreach($result in @('UNKNOWN','SUCCEEDED','SUCCEEDED')){$null=Api "M06 refund $result" POST "/api/v1/platform/refunds/$($refund.id)/simulate-result" $admin @{result=$result}}
 $null=Api 'M06 excess refund rejected' POST "/api/v1/tenant/payments/$($pay.id)/refunds" $hA @{amountMinor=4036} @(409) "$run-over-refund"
 $rec=Api 'M07 reconcile paid/refunded' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-rec"
 Check 'M07 clean reconciliation' ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 10)
 $late=Api 'M05 late event' POST /api/v1/ingest/usage-events $kh (Event 'late' 5 ([DateTime]::Parse($at).AddMonths(-1).ToUniversalTime().ToString('o'))) @(202)
 WaitEvent $late.id
 $adjust=Api 'M05 late adjustment' POST "/api/v1/platform/billing/periods/$period/adjust-late-usage" $admin
 Check 'M05 adjustment amount' ($adjust.amountMinor -eq 5) "amount=$($adjust.amountMinor)"
 $unchanged=Api 'M05 original invoice snapshot' GET "/api/v1/tenant/invoices/$($invoice.id)" $hA
 Check 'M05 history unchanged' ($unchanged.totalAmountMinor -eq 5035) "original=$($unchanged.totalAmountMinor)"
 $lateRec=Api 'M06 reconcile after late adjustment' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-late-rec"
 Check 'M06 adjustment posts AR and refreshes status' ($lateRec.differences.Count -eq 0) ($lateRec|ConvertTo-Json -Compress -Depth 10)
 # New active subscription for bounded JMeter baseline.
 $perf=Api 'M08 performance subscription' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-perf"
 $perf=Api 'M08 activate performance subscription' POST "/api/v1/tenant/subscriptions/$($perf.id)/activate" $hA
 $state=@{run=$run;out=$out;tenantA=$tenantA;tenantB=$tenantB;userA=$userA;userB=$userB;product=$prod;plan=$plan;price=$price;version=$ver;meter=$meter;subscription=$sub.id;period=$period;invoice=$invoice.id;payment=$pay.id;adjustment=$adjust.id;perfSubscription=$perf.id;keyId=$apiKey.id;keyIdB=$apiKeyB.id}
 $state|ConvertTo-Json|Set-Content "$out/state.json"
 # Credentials are only kept in a temporary local file for the follow-up runner, never in evidence.
 @{admin=$admin;hA=$hA;hB=$hB;kh=$kh;apiKeyB=$apiKeyB.apiKey}|Export-Clixml (Join-Path $env:TEMP "$run-auth.xml")
 Check 'M06 balanced individual ledger transactions' ((Sql "SELECT COUNT(*) FROM (SELECT t.id FROM mb_ledger_transaction t JOIN mb_ledger_entry e ON e.transaction_id=t.id WHERE t.tenant_id=$tenantA GROUP BY t.id HAVING SUM(IF(e.direction='DEBIT',e.amount_minor,-e.amount_minor))<>0) q;") -eq 0) 'unbalanced transactions=0'
 Write-Host "RESULT_PATH=$out"
}finally{
 $checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"
 $http|ConvertTo-Json -Depth 20|Set-Content "$out/http.json"
}



