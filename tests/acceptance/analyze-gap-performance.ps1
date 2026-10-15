param([Parameter(Mandatory)][string]$Run,[Parameter(Mandatory)][string]$Label)
$ErrorActionPreference='Stop';Set-Location (Join-Path $PSScriptRoot '../..')
$state=Get-Content "tests/results/$Run/state.json" -Raw|ConvertFrom-Json
$out=Join-Path (Get-Location) "tests/results/G01-G04-20261012/$Label"
. "$PSScriptRoot/live-helpers.ps1"
$rows=[Collections.Generic.List[object]]::new()
foreach($phase in (Get-Content "$out/performance.json" -Raw|ConvertFrom-Json)){
 $jtl=Import-Csv "$out/$($phase.phase)/results.jtl"
 $starts=@($jtl|ForEach-Object {[long]$_.timeStamp});$ends=@($jtl|ForEach-Object {[long]$_.timeStamp+[long]$_.elapsed})
 $startMillis=($starts|Measure-Object -Minimum).Minimum;$endMillis=($ends|Measure-Object -Maximum).Maximum
 $start=[DateTimeOffset]::FromUnixTimeMilliseconds($startMillis).UtcDateTime.ToString('yyyy-MM-dd HH:mm:ss.fff')
 $end=[DateTimeOffset]::FromUnixTimeMilliseconds($endMillis).UtcDateTime.ToString('yyyy-MM-dd HH:mm:ss.fff')
 $seconds=($endMillis-$startMillis)/1000
 $processed=[long](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$($state.tenantA) AND processed_at BETWEEN '$start' AND '$end';")
 $pending=[long](Sql "SELECT COUNT(*) FROM mb_usage_event WHERE tenant_id=$($state.tenantA) AND received_at<='$end' AND (processed_at IS NULL OR processed_at>'$end');")
 $latencies=@(Sql "SELECT TIMESTAMPDIFF(MICROSECOND,received_at,processed_at)/1000 FROM mb_usage_event WHERE tenant_id=$($state.tenantA) AND source_id='$Label-$($phase.phase)' AND processed_at IS NOT NULL ORDER BY TIMESTAMPDIFF(MICROSECOND,received_at,processed_at);")
 $p95=if($latencies.Count){[double]$latencies[[math]::Max(0,[math]::Ceiling($latencies.Count*.95)-1)]}else{$null}
 $p99=if($latencies.Count){[double]$latencies[[math]::Max(0,[math]::Ceiling($latencies.Count*.99)-1)]}else{$null}
 $rows.Add([ordered]@{phase=$phase.phase;requests=$phase.requests;httpQps=$phase.throughput;httpP95Ms=$phase.p95;httpP99Ms=$phase.p99;processedDuringHttpWindow=$processed;processQpsDuringHttpWindow=[math]::Round($processed/$seconds,2);pendingAtHttpEnd=$pending;ingestToProcessedP95Ms=$p95;ingestToProcessedP99Ms=$p99;httpStartUtc=$start;httpEndUtc=$end})
}
$rows|ConvertTo-Json -Depth 8|Set-Content "$out/detailed-performance.json"
$rows|ForEach-Object {[pscustomobject]$_}|Format-Table phase,httpQps,httpP95Ms,httpP99Ms,processQpsDuringHttpWindow,pendingAtHttpEnd -AutoSize
$samples=Get-Content "$out/diagnostics.json" -Raw|ConvertFrom-Json
$resources=foreach($group in ($samples|Where-Object {$_.data}|Group-Object phase)){
 [ordered]@{
  phase=$group.Name
  samples=$group.Count
  maxDbActive=($group.Group|ForEach-Object {$_.data.metrics.'hikaricp.connections.active'[0].VALUE}|Measure-Object -Maximum).Maximum
  maxDbWaiting=($group.Group|ForEach-Object {$_.data.metrics.'hikaricp.connections.pending'[0].VALUE}|Measure-Object -Maximum).Maximum
  maxJvmThreads=($group.Group|ForEach-Object {$_.data.metrics.'jvm.threads.live'[0].VALUE}|Measure-Object -Maximum).Maximum
  maxProcessCpu=($group.Group|ForEach-Object {$_.data.metrics.'process.cpu.usage'[0].VALUE}|Measure-Object -Maximum).Maximum
  maxMqReady=($group.Group|ForEach-Object {$_.mq.ready}|Measure-Object -Maximum).Maximum
  maxMqUnacked=($group.Group|ForEach-Object {$_.mq.unacked}|Measure-Object -Maximum).Maximum
  maxOutboxAgeSeconds=($group.Group|ForEach-Object {$_.data.oldestOutboxSeconds}|Measure-Object -Maximum).Maximum
 }
}
$resources|ConvertTo-Json -Depth 8|Set-Content "$out/resource-summary.json"