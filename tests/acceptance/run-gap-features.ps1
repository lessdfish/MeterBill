param([Parameter(Mandatory)][string]$Run,[string]$BaseUrl='http://localhost:8086')
$ErrorActionPreference='Stop';Set-Location (Join-Path $PSScriptRoot '../..')
$state=Get-Content "tests/results/$Run/state.json" -Raw|ConvertFrom-Json
$auth=Import-Clixml (Join-Path $env:TEMP "$Run-auth.xml")
$admin=$auth.admin;$hA=$auth.hA
$out=Join-Path (Get-Location) 'tests/results/G01-G04-20261012/live-features';New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new();$http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$null=Api 'G04 anonymous diagnostics denied' GET /api/v1/platform/diagnostics @{} $null @(401)
$null=Api 'G04 tenant diagnostics denied' GET /api/v1/platform/diagnostics $hA $null @(403)
$diag=Api 'G04 admin metrics' GET /api/v1/platform/diagnostics $admin
Check 'G04 Hikari and thread metrics present' ($null -ne $diag.metrics.'hikaricp.connections.active' -and $null -ne $diag.metrics.'jvm.threads.live') 'metrics exposed only to platform admin'
$models=Api 'G01 model directory' GET "/api/v1/platform/products/$($state.product)/models" $admin
Check 'G01 immutable mapping exists' (@($models|Where-Object modelCode -eq ACCEPTANCE_MODEL).Count -eq 1) ($models|ConvertTo-Json -Compress -Depth 5)
$null=Api 'G01 tenant cannot create model' POST "/api/v1/platform/products/$($state.product)/models" $hA @{modelCode='DENIED';name='Denied';meterIds=@($state.meter)} @(403)
$invoiceId=Sql "SELECT id FROM mb_invoice WHERE subscription_id=$($state.perfSubscription);"
$invoice=Api 'G02 loaded unpaid invoice' GET "/api/v1/tenant/invoices/$invoiceId" $hA
$payment=Api 'G02 create payment' POST "/api/v1/tenant/invoices/$invoiceId/payments" $hA @{amountMinor=$invoice.totalAmountMinor} @(200) "$Run-feature-pay"
$null=Api 'G02 unknown payment' POST "/api/v1/platform/payments/$($payment.id)/simulate-result" $admin @{result='UNKNOWN'}
$null=Api 'G02 independent channel success no callback' POST /api/v1/platform/channel/observations $admin @{tenantId=[long]$state.tenantA;externalReference=$payment.externalReference;type='CHARGE';currency='CNY';amountMinor=$payment.amountMinor;status='SUCCEEDED'}
$pending=Api 'G02 local remains unknown' GET "/api/v1/tenant/payments/$($payment.id)" $hA
Check 'G02 observation did not fake callback' ($pending.status -eq 'UNKNOWN') "status=$($pending.status)"
$null=Api 'G02 tenant cannot query channel' POST "/api/v1/platform/payments/$($payment.id)/query-channel" $hA $null @(403)
$paid=Api 'G02 recover by query' POST "/api/v1/platform/payments/$($payment.id)/query-channel" $admin
Check 'G02 query converges' ($paid.status -eq 'SUCCEEDED') "status=$($paid.status)"
$null=Api 'G02 query idempotent' POST "/api/v1/platform/payments/$($payment.id)/query-channel" $admin
$null=Api 'G02 tenant reversal denied' POST "/api/v1/platform/payments/$($payment.id)/reversal" $hA @{reason='Acceptance receipt correction'} @(403)
$reversal=Api 'G02 reverse successful payment' POST "/api/v1/platform/payments/$($payment.id)/reversal" $admin @{reason='Acceptance receipt correction'}
$again=Api 'G02 repeat reversal' POST "/api/v1/platform/payments/$($payment.id)/reversal" $admin @{reason='Acceptance receipt correction'}
Check 'G02 one reversal' ($reversal -eq $again) "id=$reversal"
$unpaid=Api 'G02 invoice after reversal' GET "/api/v1/tenant/invoices/$invoiceId" $hA
Check 'G02 obligation restored history unchanged' ($unpaid.status -eq 'ISSUED' -and $unpaid.totalAmountMinor -eq $invoice.totalAmountMinor) "status=$($unpaid.status),total=$($unpaid.totalAmountMinor)"
$null=Api 'G02 reversed payment cannot refund' POST "/api/v1/tenant/payments/$($payment.id)/refunds" $hA @{amountMinor=1} @(409) "$Run-refund-denied"
$null=Api 'G02 refunded payment cannot reverse' POST "/api/v1/platform/payments/$($state.payment)/reversal" $admin @{reason='Must be denied'} @(409)
$rec=Api 'G03 final complete reconciliation' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) ([guid]::NewGuid().ToString())
Check 'G03 no differences after query and reversal' ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
if(@($checks|Where-Object status -eq FAIL).Count){throw 'Live feature checks failed'}
