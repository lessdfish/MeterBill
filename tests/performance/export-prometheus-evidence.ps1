param(
    [Parameter(Mandatory)][string]$ResultsJtl,
    [string]$PrometheusUrl = 'http://127.0.0.1:19092',
    [string]$OutputHtml = 'docs/evidence/performance/prometheus-monitoring-500.html'
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$rows = @(Import-Csv (Resolve-Path $ResultsJtl))
if ($rows.Count -eq 0) { throw 'The JTL file contains no samples.' }
$minMs = [long](($rows.timeStamp | Measure-Object -Minimum).Minimum)
$maxMs = [long](($rows.timeStamp | Measure-Object -Maximum).Maximum)
$start = [DateTimeOffset]::FromUnixTimeMilliseconds($minMs).AddSeconds(-15)
$end = [DateTimeOffset]::FromUnixTimeMilliseconds($maxMs).AddSeconds(30)

$queries = [ordered]@{
    throughput = 'sum by (instance) (rate(http_server_requests_seconds_count{uri="/api/v1/ingest/usage-events"}[30s]))'
    cpu = 'process_cpu_usage * 100'
    heap = 'jvm_memory_used_bytes{area="heap"} / 1024 / 1024'
    hikari = '{__name__=~"hikaricp_connections_(active|pending)"}'
    outbox = 'meterbill_outbox_backlog or meterbill_outbox_oldest_age_seconds'
}
$series = [ordered]@{}
foreach ($entry in $queries.GetEnumerator()) {
    $uri = "$PrometheusUrl/api/v1/query_range?query=$([uri]::EscapeDataString($entry.Value))&start=$($start.ToUnixTimeSeconds())&end=$($end.ToUnixTimeSeconds())&step=5"
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 30
    if ($response.status -ne 'success') { throw "Prometheus query failed: $($entry.Key)" }
    $series[$entry.Key] = @($response.data.result)
}

$payload = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    windowStart = $start.ToString('o')
    windowEnd = $end.ToString('o')
    queries = $queries
    series = $series
} | ConvertTo-Json -Depth 12 -Compress

$html = @'
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>MeterBill 500 req/s 监控证据</title>
<style>
body{margin:0;background:#f3f5f8;color:#172033;font:14px/1.5 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto;padding:28px}h1{margin:0 0 4px;font-size:28px}.meta{color:#5c667a;margin-bottom:20px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{background:#fff;border:1px solid #dce2ea;border-radius:12px;padding:16px;box-shadow:0 3px 10px #1720330d}.card h2{font-size:17px;margin:0 0 8px}.chart{height:245px}.legend{display:flex;gap:14px;flex-wrap:wrap;color:#4d586c;font-size:12px}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:5px}.foot{margin-top:16px;background:#eaf7ef;border-left:4px solid #16834a;padding:12px 16px;border-radius:6px}svg text{fill:#6a7486;font-size:11px}.axis{stroke:#ccd3de;stroke-width:1}.gridline{stroke:#e8ecf2;stroke-width:1}</style></head><body><main>
<h1>MeterBill 本机双实例：500 req/s 监控证据</h1><div class="meta" id="meta"></div>
<div class="grid"><section class="card"><h2>HTTP 接收吞吐（req/s，按实例）</h2><div id="throughput" class="chart"></div></section>
<section class="card"><h2>应用进程 CPU（%）</h2><div id="cpu" class="chart"></div></section>
<section class="card"><h2>JVM Heap 使用量（MiB）</h2><div id="heap" class="chart"></div></section>
<section class="card"><h2>HikariCP Active / Pending</h2><div id="hikari" class="chart"></div></section>
<section class="card" style="grid-column:1/-1"><h2>Outbox backlog / oldest age</h2><div id="outbox" class="chart"></div></section></div>
<div class="foot">数据源：Prometheus query_range；测试请求仍经过 Nginx，两个 Spring Boot 实例均被采集。正式吞吐结论以 formal500 JTL 的连续 300 秒窗口为准。</div>
</main><script>
const data=__PAYLOAD__;document.getElementById('meta').textContent=`采集窗口：${data.windowStart} ～ ${data.windowEnd}　生成时间：${data.generatedAt}`;
const colors=['#1769aa','#e67e22','#16834a','#8e44ad','#c0392b','#008b8b'];
function label(s){const m=s.metric||{};return [m.instance,m.__name__,m.area,m.pool].filter(Boolean).join(' · ')||'value'}
function draw(id,series){const host=document.getElementById(id),W=host.clientWidth||700,H=210,p={l:52,r:18,t:12,b:30};let pts=[];series.forEach(s=>(s.values||[]).forEach(v=>pts.push([+v[0],+v[1]])));if(!pts.length){host.textContent='当前窗口无数据';return}const xs=pts.map(x=>x[0]),ys=pts.map(x=>x[1]).filter(Number.isFinite),xmin=Math.min(...xs),xmax=Math.max(...xs),ymin=Math.min(0,...ys),ymax=Math.max(...ys,1),sx=x=>p.l+(x-xmin)/(xmax-xmin||1)*(W-p.l-p.r),sy=y=>H-p.b-(y-ymin)/(ymax-ymin||1)*(H-p.t-p.b);let svg=`<svg viewBox="0 0 ${W} ${H}" width="100%" height="210">`;for(let i=0;i<=4;i++){let y=p.t+i*(H-p.t-p.b)/4,val=ymax-i*(ymax-ymin)/4;svg+=`<line class="gridline" x1="${p.l}" y1="${y}" x2="${W-p.r}" y2="${y}"/><text x="4" y="${y+4}">${val.toFixed(val>20?0:1)}</text>`}svg+=`<line class="axis" x1="${p.l}" y1="${H-p.b}" x2="${W-p.r}" y2="${H-p.b}"/>`;series.forEach((s,i)=>{const values=(s.values||[]).filter(v=>Number.isFinite(+v[1]));const d=values.map((v,j)=>`${j?'L':'M'}${sx(+v[0]).toFixed(1)},${sy(+v[1]).toFixed(1)}`).join(' ');svg+=`<path d="${d}" fill="none" stroke="${colors[i%colors.length]}" stroke-width="2"/>`});svg+=`<text x="${p.l}" y="${H-8}">${new Date(xmin*1000).toLocaleTimeString()}</text><text text-anchor="end" x="${W-p.r}" y="${H-8}">${new Date(xmax*1000).toLocaleTimeString()}</text></svg>`;svg+='<div class="legend">'+series.map((s,i)=>`<span><i class="dot" style="background:${colors[i%colors.length]}"></i>${label(s)}</span>`).join('')+'</div>';host.innerHTML=svg}
Object.entries(data.series).forEach(([k,v])=>draw(k,v));
</script></body></html>
'@
$html = $html.Replace('__PAYLOAD__', $payload)

$outputPath = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputHtml))
New-Item -ItemType Directory -Path (Split-Path $outputPath) -Force | Out-Null
$html | Set-Content $outputPath -Encoding utf8
[pscustomobject]@{ output = $outputPath; windowStart = $start; windowEnd = $end; series = ($series.Values | ForEach-Object Count | Measure-Object -Sum).Sum } | ConvertTo-Json
