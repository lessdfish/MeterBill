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
 # Fresh target plan and historical fixture for next-period plan-change/renewal.
 $targetPlan=Sql "INSERT INTO mb_plan(product_id,plan_code,name,status) VALUES($prod,'${run}_NEXT','Acceptance next plan','ACTIVE'); SELECT LAST_INSERT_ID();"
 $targetPrice=Sql "INSERT INTO mb_price(plan_id,currency,billing_period) VALUES($targetPlan,'CNY','MONTHLY'); SELECT LAST_INSERT_ID();"
 $targetVersion=Sql "INSERT INTO mb_price_version(price_id,version_no,base_amount_minor,status,effective_at) VALUES($targetPrice,1,7000,'PUBLISHED','2026-08-01 00:00:00'); SELECT LAST_INSERT_ID();"
 $renewSub=Api 'M03 renewal fixture' POST /api/v1/tenant/subscriptions $hA @{planId=$plan} @(200) "$run-renew"
 $renewSub=Api 'M03 activate renewal fixture' POST "/api/v1/tenant/subscriptions/$($renewSub.id)/activate" $hA
 $null=Sql "UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH WHERE subscription_id=$($renewSub.id);"
 $changed=Api 'M03 schedule next-period plan' POST "/api/v1/tenant/subscriptions/$($renewSub.id)/plan-changes" $hA @{planId=$targetPlan} @(200) "$run-plan-change"
 Check 'M03 plan remains old before renewal' ($changed.planId -eq $plan) "plan=$($changed.planId)"
 $renewed=Api 'M03 renew applies scheduled plan' POST "/api/v1/tenant/subscriptions/$($renewSub.id)/renew" $hA
 Check 'M03 renewal new price and month' ($renewed.planId -eq $targetPlan -and $renewed.baseAmountMinor -eq 7000 -and ([datetime]$renewed.periodStart).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss') -eq '2026-09-30T16:00:00') ($renewed|ConvertTo-Json -Compress)
 $null=Api 'M03 renewal cannot duplicate current period' POST "/api/v1/tenant/subscriptions/$($renewSub.id)/renew" $hA $null @(409)
 Check 'M03 two periods after one renewal' ((Sql "SELECT COUNT(*) FROM mb_subscription_period WHERE subscription_id=$($renewSub.id);") -eq 2) 'periods=2'
 $planExplain=Sql "EXPLAIN SELECT id FROM mb_usage_meter WHERE product_id=$prod AND meter_code='INPUT_TOKENS';"
 $planExplain|Set-Content "$out/explain-meter-tabular.tsv"
 Check 'M08 unique index lookup confirmed' (($planExplain -join ' ') -match 'uk_meter_product_code') ($planExplain -join ' ')
 $pending=Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenantA AND status<>'PROCESSED';"
 Check 'M08 final asynchronous drain' ($pending -eq 0) "pending=$pending; earlier 60-second observation was insufficient, final DB check at $([DateTime]::UtcNow.ToString('o'))"
 $rec=Api 'M08 post-load reconciliation' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) "$run-post-load"
 $rec|ConvertTo-Json -Depth 15|Set-Content "$out/post-load-reconciliation.json"
 Check 'M08 all financial invariants after load' ($rec.differences.Count -eq 0) "differences=$($rec.differences.Count); unpaid invoice/late-adjustment posting gaps persist"
 # Record explicit RBAC payment/refund write behavior with harmless synthetic obligations.
 $cfg=@{};Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
 $l=Api 'M01 viewer login follow-up' POST /api/v1/auth/login @{} @{email="${run}_viewer@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
 $hv=@{Authorization="Bearer $($l.accessToken)";'X-Tenant-Id'="$tenantA"}
 $v=Api 'M01 viewer refund write boundary' POST "/api/v1/tenant/payments/$($state.payment)/refunds" $hv @{amountMinor=1} @(200,403) "$run-viewer-refund"
 Check 'M01 VIEWER cannot request refund' ($http[$http.Count-1].status -eq 403) "HTTP $($http[$http.Count-1].status); refund=$($v.id)"
 $null=Api 'M01 revoke main acceptance key' POST "/api/v1/tenant/api-keys/$($state.keyId)/revoke" $hA
 # Disable only this run's dedicated accounts; preserve billing facts for inspection.
 $null=Sql "UPDATE mb_user SET status='DISABLED' WHERE email LIKE '${run}%@example.test';"
 $meta=[ordered]@{date=[DateTime]::UtcNow.ToString('o');git=(git -c safe.directory='F:/test_file/MeterBill 备份文件/MeterBill' rev-parse HEAD);dirty=$true;run=$run;java='21.0.10';host=(Get-CimInstance Win32_ComputerSystem|Select-Object TotalPhysicalMemory,NumberOfLogicalProcessors);cpu=(Get-CimInstance Win32_Processor|Select-Object Name);docker=(docker info --format '{{.NCPU}} CPUs {{.MemTotal}} bytes');notes='IDEA 8085 + temporary 8086; same working tree compiled classes; JMeter on 8085 while both consumers active'}
 $meta|ConvertTo-Json -Depth 8|Set-Content "$out/environment.json"
 Get-FileHash src/main/java/com/meterbill/billing/service/InvoiceService.java,src/main/java/com/meterbill/payment/service/PaymentService.java,src/main/java/com/meterbill/product/service/UsagePricingService.java,target/classes/com/meterbill/billing/service/InvoiceService.class,target/classes/com/meterbill/payment/service/PaymentService.class,target/classes/com/meterbill/product/service/UsagePricingService.class | Select-Object Path,Hash | ConvertTo-Json | Set-Content "$out/version-hashes.json"
 $checks|Group-Object status|Select-Object Name,Count|Format-Table
}finally{
 $checks|ConvertTo-Json -Depth 20|Set-Content "$out/checks.json"
 $instancePid=[int](Get-Content tests/results/second-instance.pid)
 $p=Get-CimInstance Win32_Process -Filter "ProcessId=$instancePid"
 if($p -and $p.CommandLine -like '*--server.port=8086*') {Stop-Process -Id $instancePid}
 foreach($r in @($run,'ACC_20261011_201317')){$f=Join-Path $env:TEMP "$r-auth.xml";if(Test-Path -LiteralPath $f){Remove-Item -LiteralPath $f}}
 docker ps --format '{{.Names}} {{.Status}}'
}

