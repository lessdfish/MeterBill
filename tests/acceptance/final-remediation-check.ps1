param([string]$Run='FIX_20261011_212912',[string]$BaseUrl='http://localhost:8085')
$ErrorActionPreference='Stop'; Set-Location (Join-Path $PSScriptRoot '../..')
$out=Join-Path (Get-Location) "tests/results/$Run/final-check"
New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new(); $http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$cfg=@{}; Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
foreach($prefix in @('ACC_20261011_201317','ACC_20261011_201407',$Run)){
 $tenant=Sql "SELECT id FROM mb_tenant WHERE tenant_code='${prefix}_A';"
 $null=Sql "UPDATE mb_user SET status='ACTIVE' WHERE email='${prefix}_A@example.test';"
 try {
  $login=Api 'fixture login' POST /api/v1/auth/login @{} @{email="${prefix}_A@example.test";password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
  $headers=@{Authorization="Bearer $($login.accessToken)";'X-Tenant-Id'="$tenant"}
  $rec=Api "fresh tenant $tenant reconciliation" POST /api/v1/tenant/reconciliation/runs $headers $null @(200) ([guid]::NewGuid().ToString())
  Check "tenant $tenant zero differences" ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
 } finally { $null=Sql "UPDATE mb_user SET status='DISABLED' WHERE email='${prefix}_A@example.test';" }
}
Check 'unauthorized fixture subscription canceled' ((Sql "SELECT status FROM mb_subscription WHERE id=7 AND tenant_id=4;") -eq 'CANCELED') 'subscription=7'
Check 'unauthorized fixture refund failed' ((Sql "SELECT status FROM mb_refund WHERE id=4 AND tenant_id=4;") -eq 'FAILED') 'refund=4'
Check 'V14 applied once' ((Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE version='14' AND success=1;") -eq 1) 'V14 successful count=1'
Check 'all acceptance users disabled' ((Sql "SELECT COUNT(*) FROM mb_user WHERE (email LIKE 'ACC_20261011_%@example.test' OR email LIKE '${Run}%@example.test') AND status<>'DISABLED';") -eq 0) 'enabled=0'
Check 'fixed events processed' ((Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=6 AND status<>'PROCESSED';") -eq 0) 'pending=0'
Check 'fixed events all have processed marker' ((Sql "SELECT COUNT(*) FROM mb_usage_event e LEFT JOIN mb_usage_event_processed p ON p.usage_event_id=e.id WHERE e.tenant_id=6 AND p.usage_event_id IS NULL;") -eq 0) 'missing=0'
Check 'refunds within successful payments' ((Sql "SELECT COUNT(*) FROM (SELECT p.id FROM mb_payment p JOIN mb_refund r ON r.payment_id=p.id WHERE p.tenant_id IN (2,4,6) AND r.status IN ('PENDING','UNKNOWN','SUCCEEDED') GROUP BY p.id,p.amount_minor HAVING SUM(r.amount_minor)>p.amount_minor) x;") -eq 0) 'over-limit=0'
if(@($checks|Where-Object status -eq 'FAIL').Count){throw 'Final checks failed'}
