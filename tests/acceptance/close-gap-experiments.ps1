param([string]$BaseUrl='http://localhost:8086')
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$out=Join-Path (Get-Location) 'tests/results/G01-G04-20261012/close'
New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new()
$http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$cfg=@{}
Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
foreach($run in @('GAP_20261012_093522','GAP_20261012_150537','GAP_20261012_152605')) {
    $state=Get-Content "tests/results/$run/state.json" -Raw|ConvertFrom-Json
    foreach($label in @('A','B')) {
        $email="${run}_${label}@example.test"
        $user=if($label -eq 'A'){$state.userA}else{$state.userB}
        $tenant=if($label -eq 'A'){$state.tenantA}else{$state.tenantB}
        $key=if($label -eq 'A'){$state.keyId}else{$state.keyIdB}
        if((Sql "SELECT status FROM mb_user WHERE id=$user AND email='$email';") -eq 'ACTIVE') {
            $login=Api "$run $label cleanup login" POST /api/v1/auth/login @{} @{email=$email;password=$cfg.METERBILL_BOOTSTRAP_PASSWORD}
            $headers=@{Authorization="Bearer $($login.accessToken)";'X-Tenant-Id'="$tenant"}
            $null=Api "$run $label revoke experiment key" POST "/api/v1/tenant/api-keys/$key/revoke" $headers
            $null=Sql "UPDATE mb_user SET status='DISABLED' WHERE id=$user AND email='$email';"
        }
        Check "$run $label account disabled" ((Sql "SELECT status FROM mb_user WHERE id=$user AND email='$email';") -eq 'DISABLED') 'synthetic account only'
        Check "$run $label key revoked" ((Sql "SELECT status FROM mb_api_key WHERE id=$key AND tenant_id=$tenant;") -eq 'REVOKED') 'synthetic key only'
    }
    $tempAuth=Join-Path $env:TEMP "$run-auth.xml"
    if(Test-Path -LiteralPath $tempAuth){Remove-Item -LiteralPath $tempAuth}
}
Check '8086 remains healthy' ((Invoke-RestMethod "$BaseUrl/actuator/health").status -eq 'UP') 'UP'
if(@($checks|Where-Object status -eq FAIL).Count){throw 'Experiment cleanup verification failed'}
