import { performance } from 'node:perf_hooks'

const baseUrl = process.env.PERF_BASE_URL || 'http://127.0.0.1:8086/api/v1'
const token = process.env.PERF_TOKEN
const tenantId = process.env.PERF_TENANT_ID
const concurrency = Number(process.env.PERF_CONCURRENCY || 50)
const requestCount = Number(process.env.PERF_REQUESTS || 2000)

if (!token || !tenantId) {
  console.error('PERF_TOKEN and PERF_TENANT_ID are required')
  process.exit(2)
}
if (!Number.isInteger(concurrency) || concurrency < 1 || !Number.isInteger(requestCount) || requestCount < 1) {
  console.error('PERF_CONCURRENCY and PERF_REQUESTS must be positive integers')
  process.exit(2)
}

const paths = [
  '/tenant/dashboard/overview',
  '/tenant/invoices?page=1&size=20',
  '/tenant/payments?page=1&size=20',
  '/tenant/subscriptions?page=1&size=20',
  '/tenant/usage-events?page=1&size=20',
  '/tenant/reconciliation/differences?limit=100',
]
const latencies = []
const errorSamples = []
let nextIndex = 0
let succeeded = 0
let failed = 0

async function worker() {
  while (true) {
    const index = nextIndex++
    if (index >= requestCount) return
    const path = paths[index % paths.length]
    const started = performance.now()
    try {
      const response = await fetch(baseUrl + path, {
        headers: { Authorization: `Bearer ${token}`, 'X-Tenant-Id': tenantId },
      })
      const payload = await response.json()
      const elapsed = performance.now() - started
      latencies.push(elapsed)
      if (response.status === 200 && payload.code === 'SUCCESS') succeeded += 1
      else {
        failed += 1
        if (errorSamples.length < 10) errorSamples.push({ path, status: response.status, code: payload.code })
      }
    } catch (error) {
      latencies.push(performance.now() - started)
      failed += 1
      if (errorSamples.length < 10) errorSamples.push({ path, error: String(error) })
    }
  }
}

function percentile(sorted, value) {
  if (!sorted.length) return 0
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * value) - 1)]
}

const started = performance.now()
await Promise.all(Array.from({ length: Math.min(concurrency, requestCount) }, worker))
const durationMs = performance.now() - started
latencies.sort((left, right) => left - right)

const result = {
  scenario: 'authenticated-read-mix',
  concurrency,
  requests: requestCount,
  succeeded,
  failed,
  errorRatePercent: Number(((failed / requestCount) * 100).toFixed(4)),
  durationMs: Number(durationMs.toFixed(2)),
  qps: Number((requestCount / (durationMs / 1000)).toFixed(2)),
  latencyMs: {
    p50: Number(percentile(latencies, 0.5).toFixed(2)),
    p95: Number(percentile(latencies, 0.95).toFixed(2)),
    p99: Number(percentile(latencies, 0.99).toFixed(2)),
    max: Number((latencies.at(-1) || 0).toFixed(2)),
  },
  errorSamples,
}

console.log(JSON.stringify(result, null, 2))
if (failed > 0) process.exit(1)
