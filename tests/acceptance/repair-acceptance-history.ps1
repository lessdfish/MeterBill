param([string]$Run='FIX_20261011_212912',[string]$BaseUrl='http://localhost:8085')
$ErrorActionPreference='Stop';Set-Location (Join-Path $PSScriptRoot '../..')
$out=Join-Path (Get-Location) "tests/results/$Run/historical-repair";New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new();$http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$cfg=@{};Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
$l=Api 'platform login for repair' POST /api/v1/auth/login @{} @{email=$cfg.METERBILL_BOOTSTRAP_EMAIL;password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
$admin=@{Authorization="Bearer $($l.accessToken)"}
foreach($prefix in @('ACC_20261011_201317','ACC_20261011_201407')){
 $tenant=Sql "SELECT id FROM mb_tenant WHERE tenant_code='${prefix}_A';"
 $before=Sql "SELECT id,period_id,total_amount_minor,status FROM mb_invoice WHERE tenant_id=$tenant ORDER BY id;"
 $before|Set-Content "$out/$prefix-before.tsv"
 foreach($period in (Sql "SELECT period_id FROM mb_invoice WHERE tenant_id=$tenant ORDER BY id;")){
  $null=Api "repair period $period" POST "/api/v1/platform/billing/periods/$period/issue" $admin
  $null=Api "idempotent repair period $period" POST "/api/v1/platform/billing/periods/$period/issue" $admin
 }
 $after=Sql "SELECT id,period_id,total_amount_minor,status FROM mb_invoice WHERE tenant_id=$tenant ORDER BY id;"
 $after|Set-Content "$out/$prefix-after.tsv"
 $null=Sql "UPDATE mb_user SET status='ACTIVE' WHERE email='${prefix}_A@example.test';"
 try{
  $l=Api 'fixture owner login' POST /api/v1/auth/login @{} @{email="${prefix}_A@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
  $h=@{Authorization="Bearer $($l.accessToken)";'X-Tenant-Id'="$tenant"}
  $rec=Api "historical tenant $tenant reconciliation" POST /api/v1/tenant/reconciliation/runs $h $null @(200) "$Run-repaired-$tenant"
  Check "historical tenant $tenant no differences" ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
  if($rec.differences.Count -eq 0){
   foreach($row in (Sql "SELECT id,status FROM mb_reconciliation_difference WHERE tenant_id=$tenant AND status IN ('OPEN','INVESTIGATING') AND difference_type IN ('INVOICE_LEDGER_MISMATCH','INVOICE_PAYMENT_STATUS_MISMATCH');")){
    $parts=$row -split "`t";$id=$parts[0]
    if($parts[1] -eq 'OPEN'){$null=Api "investigate repaired difference $id" PATCH "/api/v1/platform/reconciliation/differences/$id" $admin @{status='INVESTIGATING';note="A03/A04 repair verified in reconciliation $($rec.id)"}}
    $null=Api "resolve repaired difference $id" PATCH "/api/v1/platform/reconciliation/differences/$id" $admin @{status='RESOLVED';note="Missing entries appended idempotently; zero differences in reconciliation $($rec.id); invoice facts preserved"}
   }
  }
  if($prefix -eq 'ACC_20261011_201407'){
   if((Sql "SELECT status FROM mb_subscription WHERE id=7 AND tenant_id=$tenant;") -eq 'PENDING'){
    $null=Api 'cancel old unauthorized synthetic subscription' POST '/api/v1/tenant/subscriptions/7/cancel?immediate=true' $h
   }
   if((Sql "SELECT status FROM mb_refund WHERE id=4 AND tenant_id=$tenant;") -in @('PENDING','UNKNOWN')){
    $null=Api 'fail old unauthorized synthetic pending refund' POST /api/v1/platform/refunds/4/simulate-result $admin @{result='FAILED'}
   }
  }
 }finally{$null=Sql "UPDATE mb_user SET status='DISABLED' WHERE email='${prefix}_A@example.test';"}
}
