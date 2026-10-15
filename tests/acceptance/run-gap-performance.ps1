param([Parameter(Mandatory)][string]$Run,[Parameter(Mandatory)][string]$Label,[string]$BaseUrl='http://localhost:8086')
$ErrorActionPreference='Stop';Set-Location (Join-Path $PSScriptRoot '../..')
$state=Get-Content "tests/results/$Run/state.json" -Raw|ConvertFrom-Json
$auth=Import-Clixml (Join-Path $env:TEMP "$Run-auth.xml")
$admin=$auth.admin;$hA=$auth.hA;$kh=$auth.kh;$tenant=$state.tenantA
$out=Join-Path (Get-Location) "tests/results/G01-G04-20261012/$Label";New-Item -ItemType Directory -Force $out|Out-Null
$checks=[Collections.Generic.List[object]]::new();$http=[Collections.Generic.List[object]]::new()
. "$PSScriptRoot/live-helpers.ps1"
$stats=[Collections.Generic.List[object]]::new();$samples=[Collections.Generic.List[object]]::new()
$jmeterHome='E:/Downloads/apache-jmeter-5.6.3'
$cfg=@{};Get-Content .env|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1].Trim()]=$matches[2].Trim()}}
$rabbitHeaders=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($cfg.RABBITMQ_USER):$($cfg.RABBITMQ_PASSWORD)"))}
$rabbitPort=if($cfg.RABBITMQ_MANAGEMENT_PORT){$cfg.RABBITMQ_MANAGEMENT_PORT}else{'15672'}
foreach($phase in @(@{name='warmup';rate=25;seconds=20},@{name='step25';rate=25;seconds=45},@{name='step50';rate=50;seconds=45},@{name='step100';rate=100;seconds=45},@{name='sustain25';rate=25;seconds=300},@{name='capacity';rate=0;seconds=30})){
 if($phase.name -eq 'capacity'){
  for($i=0;$i -lt 300;$i++){if((Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenant AND status<>'PROCESSED';") -eq 0){break};Start-Sleep -Seconds 2}
 }
 $dir=Join-Path $out $phase.name;New-Item -ItemType Directory -Force $dir|Out-Null
 $props=Join-Path $env:TEMP "$Run-$Label.properties"
 @('TARGET_PORT=8086',"API_KEY=$($kh['X-API-Key'])","SUBSCRIPTION_ID=$($state.perfSubscription)",'METER_CODE=INPUT_TOKENS','MODEL_CODE=ACCEPTANCE_MODEL','THREADS=20','RAMP_UP_SECONDS=5',"DURATION_SECONDS=$($phase.seconds)","TARGET_PER_MINUTE=$($phase.rate*60)","SOURCE_ID=$Label-$($phase.name)")|Set-Content $props
 $plan=if($phase.rate -eq 0){'tests/jmeter/m08-usage-ingest.jmx'}else{'tests/jmeter/m08-bounded-capacity.jmx'}
 $before=[DateTimeOffset]::UtcNow
 try{
  $args=@('-Xms256m','-Xmx512m','-jar',"`"$jmeterHome/bin/ApacheJMeter.jar`"",'-n','-t',"`"$((Resolve-Path $plan).Path)`"",'-q',"`"$props`"",'-l',"`"$dir/results.jtl`"",'-j',"`"$dir/jmeter.log`"",'-e','-o',"`"$dir/html`"")
  $process=Start-Process 'F:/Java/java21/bin/java.exe' -ArgumentList $args -WorkingDirectory (Get-Location).Path -WindowStyle Hidden -PassThru -RedirectStandardOutput "$dir/stdout.log" -RedirectStandardError "$dir/stderr.log"
  do{
   try{
    $diag=Invoke-RestMethod "$BaseUrl/api/v1/platform/diagnostics" -Headers $admin -TimeoutSec 10
    $mq=Invoke-RestMethod "http://127.0.0.1:$rabbitPort/api/queues/%2F/meterbill.usage.queue" -Headers $rabbitHeaders -TimeoutSec 10
    $samples.Add(@{phase=$phase.name;at=[DateTimeOffset]::UtcNow.ToString('o');data=$diag.data;mq=@{ready=$mq.messages_ready;unacked=$mq.messages_unacknowledged;consumers=$mq.consumers;rates=$mq.message_stats}})
   }catch{$samples.Add(@{phase=$phase.name;error=$_.Exception.Message})}
   $samples|ConvertTo-Json -Depth 20|Set-Content "$out/diagnostics.json"
   Start-Sleep -Seconds 5;$process.Refresh()
  }while(!$process.HasExited)
  $process.WaitForExit()
  if(!(Test-Path "$dir/html/statistics.json")){throw "JMeter failed; see $dir"}
 }finally{Remove-Item -LiteralPath $props -ErrorAction SilentlyContinue}
 $s=(Get-Content "$dir/html/statistics.json" -Raw|ConvertFrom-Json).Total
 $pending=[int](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenant AND status<>'PROCESSED';")
 $count=[int](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenant AND source_id='$Label-$($phase.name)';")
 $item=[ordered]@{phase=$phase.name;offeredPerSec=$phase.rate;duration=$phase.seconds;requests=$s.sampleCount;errors=$s.errorCount;throughput=$s.throughput;p95=$s.pct2ResTime;p99=$s.pct3ResTime;pendingAtEnd=$pending;dbEvents=$count;finishedAt=[DateTimeOffset]::UtcNow.ToString('o')}
 $stats.Add($item);$stats|ConvertTo-Json -Depth 8|Set-Content "$out/performance.json"
 Check "$Label $($phase.name) accepted count" ($s.errorCount -eq 0 -and $count -eq $s.sampleCount) ($item|ConvertTo-Json -Compress)
}
$drain=[Diagnostics.Stopwatch]::StartNew()
do{$pending=[int](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$tenant AND status<>'PROCESSED';");if($pending -eq 0){break};Start-Sleep -Seconds 2}while($drain.Elapsed.TotalSeconds -lt 600)
Check 'final drain' ($pending -eq 0) "pending=$pending,seconds=$($drain.Elapsed.TotalSeconds)"
@{drainSeconds=$drain.Elapsed.TotalSeconds;pending=$pending}|ConvertTo-Json|Set-Content "$out/drain.json"
$period=Sql "SELECT id FROM mb_subscription_period WHERE subscription_id=$($state.perfSubscription) AND status='ACTIVE';"
$qty=[long](Sql "SELECT SUM(quantity) FROM mb_usage_event WHERE period_id=$period;")
Check 'exact aggregate' ((Sql "SELECT total_quantity FROM mb_usage_period_total WHERE period_id=$period;") -eq $qty) "expected=$qty"
Check 'processed markers' ((Sql "SELECT COUNT(*) FROM mb_usage_event e LEFT JOIN mb_usage_event_processed p ON p.usage_event_id=e.id WHERE e.tenant_id=$tenant AND p.usage_event_id IS NULL;") -eq 0) 'missing=0'
$null=Sql "UPDATE mb_usage_event SET occurred_at=DATE_SUB(occurred_at,INTERVAL 1 MONTH) WHERE period_id=$period; UPDATE mb_subscription_period SET active_from=DATE_SUB(active_from,INTERVAL 1 MONTH),period_start=DATE_SUB(period_start,INTERVAL 1 MONTH),period_end=period_start+INTERVAL 1 MONTH,status='ENDED' WHERE id=$period;"
$invoice=Api 'loaded invoice' POST "/api/v1/platform/billing/periods/$period/issue" $admin
$expected=5000+[math]::Min([math]::Max(0,$qty-100),10)*2+[math]::Max(0,$qty-110)
Check 'exact loaded bill' ($invoice.totalAmountMinor -eq $expected) "actual=$($invoice.totalAmountMinor),expected=$expected"
$rec=Api 'post-load reconciliation' POST /api/v1/tenant/reconciliation/runs $hA $null @(200) ([guid]::NewGuid().ToString())
Check 'post-load no differences' ($rec.differences.Count -eq 0) ($rec|ConvertTo-Json -Compress -Depth 8)
if(@($checks|Where-Object status -eq FAIL).Count){throw 'Performance invariants failed'}
