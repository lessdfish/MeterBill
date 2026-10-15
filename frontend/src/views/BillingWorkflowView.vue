<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Connection, CreditCard, Document, Goods, Refresh, TrendCharts } from '@element-plus/icons-vue'
import { api, ingestApi, unwrap } from '../api/client'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'
import { useAuthStore } from '../stores/auth'

interface Product { id: number; productCode: string; name: string; status: string }
interface Plan { id: number; productId: number; planCode: string; name: string; status: string }
interface Meter { id: number; meterCode: string; name: string; unit: string }
interface Model { id: number; modelCode: string; name: string; meterIds: number[] }
interface Subscription { id: number; planId: number; status: string; periodId?: number; periodStart?: string; periodEnd?: string }
interface UsageEvent { id: number; status: string; deliveryCount: number; lastError?: string; processedAt?: string }
interface InvoiceLine { id: number; lineType: string; description: string; quantity: number; amountMinor: number }
interface Invoice { id: number; periodId: number; currency: string; baseAmountMinor: number; usageAmountMinor: number; totalAmountMinor: number; status: string; lines: InvoiceLine[] }
interface Payment { id: number; invoiceId: number; currency: string; amountMinor: number; status: string; externalReference: string }
interface Difference { id: number; differenceType: string; referenceId: string; status: string }
interface Reconciliation { id: number; status: string; differences: Difference[] }
interface WorkflowState {
  mode: 'new' | 'existing'
  productId?: number
  planId?: number
  planName: string
  subscriptionId?: number
  subscriptionStatus?: string
  periodId?: number
  periodStart?: string
  periodEnd?: string
  apiKeyId?: number
  sourceId: string
  eventId: string
  usageEventId?: number
  usageStatus?: string
  invoiceId?: number
  invoiceStatus?: string
  invoiceTotalMinor?: number
  paymentId?: number
  paymentStatus?: string
  reconciliationId?: number
  reconciliationStatus?: string
}

const auth = useAuthStore()
const storageKey = computed(() => `meterbill.billing-workflow.${auth.tenantId}`)
const products = ref<Product[]>([])
const plans = ref<Plan[]>([])
const meters = ref<Meter[]>([])
const models = ref<Model[]>([])
const selectedMeterId = ref<number>()
const selectedModelId = ref<number>()
const invoice = ref<Invoice>()
const payment = ref<Payment>()
const reconciliation = ref<Reconciliation>()
const usageEvent = ref<UsageEvent>()
const loading = ref(false)
const action = ref('')
const form = reactive({
  planCode: `M09_${Date.now().toString().slice(-8)}`,
  planName: '全链路验证套餐',
  baseAmountYuan: 99,
  includedQuantity: 1000,
  unitAmountYuan: 0.02,
  quantity: 1350,
})

function freshState(): WorkflowState {
  const stamp = Date.now().toString(36)
  return {
    mode: 'new', planName: '', sourceId: `console-${auth.tenantId || 'tenant'}`,
    eventId: `usage-${stamp}`,
  }
}

function savedState(): Partial<WorkflowState> {
  try { return JSON.parse(sessionStorage.getItem(storageKey.value) || '{}') }
  catch { return {} }
}

const state = reactive<WorkflowState>({ ...freshState(), ...savedState() })
watch(state, () => sessionStorage.setItem(storageKey.value, JSON.stringify(state)), { deep: true })

const selectedProduct = computed(() => products.value.find(item => item.id === state.productId))
const selectedPlan = computed(() => plans.value.find(item => item.id === state.planId))
const selectedMeter = computed(() => meters.value.find(item => item.id === selectedMeterId.value))
const selectedModel = computed(() => models.value.find(item => item.id === selectedModelId.value))
const compatibleModels = computed(() => models.value.filter(item => !selectedMeterId.value || item.meterIds.includes(selectedMeterId.value)))
const completedCount = computed(() => [
  Boolean(state.planId), Boolean(state.subscriptionId && state.periodId), state.usageStatus === 'PROCESSED',
  Boolean(state.invoiceId), state.paymentStatus === 'SUCCEEDED', Boolean(state.reconciliationId),
].filter(Boolean).length)

function money(minor?: number, currency = 'CNY') {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format((minor || 0) / 100)
}

function idempotency(prefix: string) {
  return `${prefix}-${auth.tenantId}-${crypto.randomUUID()}`
}

async function chooseProduct(id?: number) {
  if (!id) return
  state.productId = id
  const [planPage, meterList, modelList] = await Promise.all([
    api.get(`/products/${id}/plans`, { params: { page: 1, size: 100 } }),
    api.get(`/products/${id}/meters`),
    api.get(`/platform/products/${id}/models`),
  ])
  plans.value = unwrap<{ records: Plan[] }>(planPage).records.filter(item => item.status === 'ACTIVE')
  meters.value = unwrap<Meter[]>(meterList)
  models.value = unwrap<Model[]>(modelList)
  if (!plans.value.some(item => item.id === state.planId)) state.planId = undefined
  if (!meters.value.some(item => item.id === selectedMeterId.value)) selectedMeterId.value = meters.value[0]?.id
  const model = models.value.find(item => item.meterIds.includes(selectedMeterId.value || -1))
  selectedModelId.value = model?.id
}

function chooseMeter(id?: number) {
  selectedMeterId.value = id
  const model = models.value.find(item => item.meterIds.includes(id || -1))
  selectedModelId.value = model?.id
}

function useExistingPlan(id?: number) {
  const plan = plans.value.find(item => item.id === id)
  state.planId = plan?.id
  state.planName = plan?.name || ''
}

async function load() {
  loading.value = true
  try {
    const page = unwrap<{ records: Product[] }>(await api.get('/products', { params: { page: 1, size: 100 } }))
    products.value = page.records.filter(item => item.status === 'ACTIVE')
    const productId = products.value.some(item => item.id === state.productId) ? state.productId : products.value[0]?.id
    if (productId) await chooseProduct(productId)
    await restoreResults()
  } finally { loading.value = false }
}

async function restoreResults() {
  const requests: Promise<unknown>[] = []
  if (state.subscriptionId) requests.push(api.get(`/tenant/subscriptions/${state.subscriptionId}`).then(response => {
    const value = unwrap<Subscription>(response); state.subscriptionStatus = value.status
    state.periodId ||= value.periodId; state.periodStart ||= value.periodStart; state.periodEnd ||= value.periodEnd
  }))
  if (state.invoiceId) requests.push(api.get(`/tenant/invoices/${state.invoiceId}`).then(response => {
    invoice.value = unwrap<Invoice>(response); state.invoiceStatus = invoice.value.status; state.invoiceTotalMinor = invoice.value.totalAmountMinor
  }))
  if (state.paymentId) requests.push(api.get(`/tenant/payments/${state.paymentId}`).then(response => {
    payment.value = unwrap<Payment>(response); state.paymentStatus = payment.value.status
  }))
  if (state.reconciliationId) requests.push(api.get(`/tenant/reconciliation/runs/${state.reconciliationId}`).then(response => {
    reconciliation.value = unwrap<Reconciliation>(response); state.reconciliationStatus = reconciliation.value.status
  }))
  await Promise.all(requests)
}

async function createPlan() {
  if (!state.productId || !selectedMeter.value || !selectedModel.value) return ElMessage.warning('请选择具备模型和计量项的产品')
  if (!form.planCode.trim() || !form.planName.trim()) return ElMessage.warning('请填写套餐编码和名称')
  action.value = 'plan'
  try {
    const productId = state.productId
    const planId = unwrap<number>(await api.post(`/products/${productId}/plans`, {
      planCode: form.planCode.trim().toUpperCase(), name: form.planName.trim(), description: 'M09 全链路业务验证套餐',
    }))
    const priceId = unwrap<number>(await api.post(`/products/${productId}/plans/${planId}/prices`, { currency: 'CNY', billingPeriod: 'MONTHLY' }))
    const versionId = unwrap<number>(await api.post(`/products/${productId}/plans/${planId}/prices/${priceId}/versions`, {
      baseAmountMinor: Math.round(form.baseAmountYuan * 100),
    }))
    await api.put(`/products/${productId}/plans/${planId}/prices/${priceId}/versions/${versionId}/usage-rules/${selectedMeter.value.id}`, {
      mode: 'PER_UNIT', includedQuantity: form.includedQuantity,
      unitAmountMinor: Math.round(form.unitAmountYuan * 100), tiers: [], revision: 0,
    })
    await api.post(`/products/${productId}/plans/${planId}/prices/${priceId}/versions/${versionId}/publish`, {
      effectiveAt: new Date(Date.now() + 1000).toISOString(), revision: 1,
    })
    await delay(1100)
    await api.post(`/products/${productId}/plans/${planId}/publish`)
    state.planId = planId; state.planName = form.planName.trim()
    await chooseProduct(productId)
    state.planId = planId
    ElMessage.success('套餐、月价和用量计费规则已发布')
  } finally { action.value = '' }
}

async function createSubscription() {
  if (!state.planId) return ElMessage.warning('请先完成套餐配置')
  action.value = 'subscription'
  try {
    const created = unwrap<Subscription>(await api.post('/tenant/subscriptions', { planId: state.planId }, {
      headers: { 'Idempotency-Key': idempotency('subscription') },
    }))
    const activated = unwrap<Subscription>(await api.post(`/tenant/subscriptions/${created.id}/activate`))
    state.subscriptionId = activated.id; state.subscriptionStatus = activated.status
    state.periodId = activated.periodId; state.periodStart = activated.periodStart; state.periodEnd = activated.periodEnd
    ElMessage.success('订阅已创建并激活，账期已生成')
  } finally { action.value = '' }
}

const delay = (milliseconds: number) => new Promise(resolve => setTimeout(resolve, milliseconds))

async function submitUsage() {
  if (!state.subscriptionId || !state.periodId || !selectedMeter.value || !selectedModel.value) return ElMessage.warning('请先完成订阅并选择有效的模型计量项')
  action.value = 'usage'
  let createdKey: { id: number; apiKey: string } | undefined
  try {
    createdKey = unwrap<{ id: number; apiKey: string }>(await api.post('/tenant/api-keys', { name: `闭环验证-${state.eventId}`, expiresInDays: 1 }))
    state.apiKeyId = createdKey.id
    const body = {
      subscriptionId: state.subscriptionId, sourceId: state.sourceId, eventId: state.eventId,
      meterCode: selectedMeter.value.meterCode, modelCode: selectedModel.value.modelCode,
      quantity: form.quantity, occurredAt: new Date().toISOString(),
    }
    usageEvent.value = unwrap<UsageEvent>(await ingestApi.post('/ingest/usage-events', body, { headers: { 'X-API-Key': createdKey.apiKey } }))
    state.usageEventId = usageEvent.value.id; state.usageStatus = usageEvent.value.status
    for (let attempt = 0; attempt < 15 && !['PROCESSED', 'FINAL_FAILED'].includes(state.usageStatus || ''); attempt += 1) {
      await delay(800)
      usageEvent.value = unwrap<UsageEvent>(await ingestApi.get(`/ingest/usage-events/${state.sourceId}/${state.eventId}`, { headers: { 'X-API-Key': createdKey.apiKey } }))
      state.usageStatus = usageEvent.value.status
    }
    if (state.usageStatus !== 'PROCESSED') {
      ElMessage.error(usageEvent.value?.lastError || '用量事件未在预期时间内处理完成')
      return
    }
    ElMessage.success('用量事件已接收并完成聚合')
  } finally {
    if (createdKey) await api.post(`/tenant/api-keys/${createdKey.id}/revoke`).catch(() => undefined)
    action.value = ''
  }
}

async function issueInvoice() {
  if (!state.subscriptionId || !state.periodId || state.usageStatus !== 'PROCESSED') return ElMessage.warning('请先等待用量事件处理完成')
  try {
    await ElMessageBox.confirm(
      '此操作会立即结束当前订阅账期并生成正式账单；订阅将进入已取消状态，不能恢复。',
      '结束账期并出账', { type: 'warning', confirmButtonText: '确认结束并出账', cancelButtonText: '返回' },
    )
  } catch { return }
  action.value = 'invoice'
  try {
    const canceled = unwrap<Subscription>(await api.post(`/tenant/subscriptions/${state.subscriptionId}/cancel`, null, { params: { immediate: true } }))
    state.subscriptionStatus = canceled.status; state.periodEnd = canceled.periodEnd
    invoice.value = unwrap<Invoice>(await api.post(`/platform/billing/periods/${state.periodId}/issue`))
    state.invoiceId = invoice.value.id; state.invoiceStatus = invoice.value.status; state.invoiceTotalMinor = invoice.value.totalAmountMinor
    ElMessage.success('账期已结束并生成正式账单')
  } finally { action.value = '' }
}

async function payInvoice() {
  if (!state.invoiceId || !state.invoiceTotalMinor) return ElMessage.warning('请先生成账单')
  action.value = 'payment'
  try {
    const created = unwrap<Payment>(await api.post(`/tenant/invoices/${state.invoiceId}/payments`, { amountMinor: state.invoiceTotalMinor }, {
      headers: { 'Idempotency-Key': idempotency('payment') },
    }))
    payment.value = unwrap<Payment>(await api.post(`/platform/payments/${created.id}/simulate-result`, { result: 'SUCCEEDED' }))
    state.paymentId = payment.value.id; state.paymentStatus = payment.value.status
    const refreshed = unwrap<Invoice>(await api.get(`/tenant/invoices/${state.invoiceId}`))
    invoice.value = refreshed; state.invoiceStatus = refreshed.status
    ElMessage.success('支付渠道回调成功，账单已入账')
  } finally { action.value = '' }
}

async function runReconciliation() {
  if (state.paymentStatus !== 'SUCCEEDED') return ElMessage.warning('请先完成支付')
  action.value = 'reconciliation'
  try {
    reconciliation.value = unwrap<Reconciliation>(await api.post('/tenant/reconciliation/runs', null, {
      headers: { 'Idempotency-Key': idempotency('reconciliation') },
    }))
    state.reconciliationId = reconciliation.value.id; state.reconciliationStatus = reconciliation.value.status
    ElMessage.success(reconciliation.value.differences.length ? '对账已完成，请处理发现的差异' : '对账完成，账务与支付一致')
  } finally { action.value = '' }
}

async function resetWorkflow() {
  await ElMessageBox.confirm('仅清除本浏览器保存的流程进度，不会删除数据库中的套餐、订阅或账单。', '开始新的闭环流程', {
    confirmButtonText: '清除并重新开始', cancelButtonText: '取消', type: 'info',
  })
  sessionStorage.removeItem(storageKey.value)
  Object.assign(state, freshState())
  Object.assign(form, { planCode: `M09_${Date.now().toString().slice(-8)}`, planName: '全链路验证套餐', baseAmountYuan: 99, includedQuantity: 1000, unitAmountYuan: 0.02, quantity: 1350 })
  invoice.value = undefined; payment.value = undefined; reconciliation.value = undefined; usageEvent.value = undefined
  await load()
}

onMounted(load)
</script>

<template>
  <MBPageHeader title="业务闭环" subtitle="在一个工作台内完成套餐发布、订阅激活、用量采集、账单生成、支付入账与对账。所有结果均来自当前数据库。">
    <template #actions>
      <div class="progress-summary"><strong>{{ completedCount }}/6</strong><span>环节完成</span></div>
      <el-button :icon="Refresh" @click="resetWorkflow">重新开始</el-button>
    </template>
  </MBPageHeader>

  <div v-loading="loading" class="workflow-shell">
    <section class="workflow-overview mb-card">
      <div class="overview-copy">
        <span class="eyebrow">M09 · BILLING OPERATIONS</span>
        <h2>套餐到回款的可操作链路</h2>
        <p>每一步使用上一步生成的真实业务 ID；页面刷新后可从当前租户的会话进度继续。</p>
      </div>
      <div class="step-rail">
        <div v-for="(item, index) in ['套餐','订阅','用量','出账','支付','对账']" :key="item" class="rail-item" :class="{ done: completedCount > index }">
          <span>{{ completedCount > index ? '✓' : index + 1 }}</span><small>{{ item }}</small>
        </div>
      </div>
    </section>

    <div class="workflow-grid">
      <section class="flow-card mb-card" :class="{ complete: state.planId }">
        <header><span class="step-no">01</span><el-icon><Goods /></el-icon><div><h3>套餐</h3><p>发布月价与用量计费规则</p></div><MBStatusTag :status="state.planId ? 'COMPLETED' : 'PENDING'" /></header>
        <div class="flow-body">
          <el-segmented v-model="state.mode" :options="[{ label: '新建套餐', value: 'new' }, { label: '使用已有套餐', value: 'existing' }]" />
          <div class="form-row"><label>所属产品</label><el-select :model-value="state.productId" filterable :disabled="Boolean(state.subscriptionId)" @change="chooseProduct"><el-option v-for="item in products" :key="item.id" :label="`${item.name} · ${item.productCode}`" :value="item.id" /></el-select></div>
          <template v-if="state.mode === 'new'">
            <div class="form-pair"><div><label>套餐编码</label><el-input v-model="form.planCode" /></div><div><label>套餐中文名</label><el-input v-model="form.planName" /></div></div>
            <div class="form-pair"><div><label>月费（元）</label><el-input-number v-model="form.baseAmountYuan" :min="0.01" :precision="2" /></div><div><label>免费用量</label><el-input-number v-model="form.includedQuantity" :min="0" /></div></div>
            <div class="form-pair"><div><label>计量项</label><el-select :model-value="selectedMeterId" @change="chooseMeter"><el-option v-for="item in meters" :key="item.id" :label="`${item.name} · ${item.meterCode}`" :value="item.id" /></el-select></div><div><label>超额单价（元）</label><el-input-number v-model="form.unitAmountYuan" :min="0" :precision="2" /></div></div>
            <div class="selection-note"><span>使用模型</span><strong>{{ selectedModel?.name || '暂无匹配模型' }}</strong><code>{{ selectedModel?.modelCode || '—' }}</code></div>
            <el-button type="primary" :loading="action === 'plan'" :disabled="Boolean(state.planId)" @click="createPlan">创建并发布套餐</el-button>
          </template>
          <template v-else>
            <div class="form-row"><label>有效套餐</label><el-select :model-value="state.planId" filterable @change="useExistingPlan"><el-option v-for="item in plans" :key="item.id" :label="`${item.name} · ${item.planCode}`" :value="item.id" /></el-select></div>
            <div class="form-pair"><div><label>计量项</label><el-select :model-value="selectedMeterId" @change="chooseMeter"><el-option v-for="item in meters" :key="item.id" :label="item.name" :value="item.id" /></el-select></div><div><label>模型</label><el-select v-model="selectedModelId"><el-option v-for="item in compatibleModels" :key="item.id" :label="item.name" :value="item.id" /></el-select></div></div>
          </template>
          <div v-if="state.planId" class="result-strip"><span>Plan ID</span><strong>#{{ state.planId }}</strong><em>{{ state.planName || selectedPlan?.name }}</em></div>
        </div>
      </section>

      <section class="flow-card mb-card" :class="{ complete: state.subscriptionId && state.periodId }">
        <header><span class="step-no">02</span><el-icon><Connection /></el-icon><div><h3>订阅</h3><p>创建订阅并激活首个账期</p></div><MBStatusTag :status="state.subscriptionStatus || 'PENDING'" /></header>
        <div class="flow-body compact">
          <div class="data-list"><span>套餐</span><strong>{{ state.planName || selectedPlan?.name || '尚未选择' }}</strong><span>订阅 ID</span><strong>{{ state.subscriptionId ? `#${state.subscriptionId}` : '—' }}</strong><span>账期 ID</span><strong>{{ state.periodId ? `#${state.periodId}` : '—' }}</strong><span>账期范围</span><strong>{{ state.periodStart ? new Date(state.periodStart).toLocaleString('zh-CN') : '—' }}<br />{{ state.periodEnd ? `至 ${new Date(state.periodEnd).toLocaleString('zh-CN')}` : '' }}</strong></div>
          <el-button type="primary" :loading="action === 'subscription'" :disabled="!state.planId || Boolean(state.subscriptionId)" @click="createSubscription">创建并激活订阅</el-button>
        </div>
      </section>

      <section class="flow-card mb-card" :class="{ complete: state.usageStatus === 'PROCESSED' }">
        <header><span class="step-no">03</span><el-icon><TrendCharts /></el-icon><div><h3>用量</h3><p>通过接入密钥提交并等待聚合</p></div><MBStatusTag :status="state.usageStatus || 'PENDING'" /></header>
        <div class="flow-body compact">
          <div class="form-pair"><div><label>本次用量</label><el-input-number v-model="form.quantity" :min="1" /></div><div><label>计量单位</label><div class="read-only">{{ selectedMeter?.unit || '—' }}</div></div></div>
          <div class="data-list"><span>事件 ID</span><strong class="mono">{{ state.eventId }}</strong><span>计量项 / 模型</span><strong>{{ selectedMeter?.meterCode || '—' }} / {{ selectedModel?.modelCode || '—' }}</strong><span>处理次数</span><strong>{{ usageEvent?.deliveryCount ?? '—' }}</strong></div>
          <el-button type="primary" :loading="action === 'usage'" :disabled="!state.subscriptionId || state.usageStatus === 'PROCESSED'" @click="submitUsage">提交真实用量</el-button>
        </div>
      </section>

      <section class="flow-card mb-card" :class="{ complete: state.invoiceId }">
        <header><span class="step-no">04</span><el-icon><Document /></el-icon><div><h3>出账</h3><p>结束当前账期并生成正式账单</p></div><MBStatusTag :status="state.invoiceStatus || 'PENDING'" /></header>
        <div class="flow-body compact">
          <div class="warning-note"><strong>状态变更</strong><span>出账前会立即结束当前账期并取消该订阅，避免对未来账期提前开票。</span></div>
          <div class="amount-box"><span>账单总额</span><strong>{{ state.invoiceId ? money(state.invoiceTotalMinor, invoice?.currency) : '待生成' }}</strong><small v-if="invoice">基础费 {{ money(invoice.baseAmountMinor) }} + 用量费 {{ money(invoice.usageAmountMinor) }}</small></div>
          <el-button type="primary" :loading="action === 'invoice'" :disabled="state.usageStatus !== 'PROCESSED' || Boolean(state.invoiceId)" @click="issueInvoice">结束账期并出账</el-button>
        </div>
      </section>

      <section class="flow-card mb-card" :class="{ complete: state.paymentStatus === 'SUCCEEDED' }">
        <header><span class="step-no">05</span><el-icon><CreditCard /></el-icon><div><h3>支付</h3><p>创建支付并模拟渠道成功回调</p></div><MBStatusTag :status="state.paymentStatus || 'PENDING'" /></header>
        <div class="flow-body compact">
          <div class="data-list"><span>Invoice ID</span><strong>{{ state.invoiceId ? `#${state.invoiceId}` : '—' }}</strong><span>支付金额</span><strong>{{ money(state.invoiceTotalMinor, invoice?.currency) }}</strong><span>Payment ID</span><strong>{{ state.paymentId ? `#${state.paymentId}` : '—' }}</strong><span>渠道流水</span><strong class="mono">{{ payment?.externalReference || '—' }}</strong></div>
          <el-button type="primary" :loading="action === 'payment'" :disabled="!state.invoiceId || state.paymentStatus === 'SUCCEEDED'" @click="payInvoice">支付并确认成功</el-button>
        </div>
      </section>

      <section class="flow-card mb-card" :class="{ complete: state.reconciliationId }">
        <header><span class="step-no">06</span><el-icon><Check /></el-icon><div><h3>对账</h3><p>核对账单、账本与支付结果</p></div><MBStatusTag :status="state.reconciliationStatus || 'PENDING'" /></header>
        <div class="flow-body compact">
          <div v-if="reconciliation" class="reconciliation-result" :class="{ clean: !reconciliation.differences.length }"><strong>{{ reconciliation.differences.length ? `${reconciliation.differences.length} 项差异` : '账务一致' }}</strong><span>{{ reconciliation.differences.length ? '请进入对账中心继续处理' : '账单、账本与支付状态核对通过' }}</span></div>
          <div v-else class="empty-result">完成支付后即可执行最终对账</div>
          <div class="data-list"><span>对账批次</span><strong>{{ state.reconciliationId ? `#${state.reconciliationId}` : '—' }}</strong><span>链路结果</span><strong>{{ completedCount === 6 ? '全链路完成' : `已完成 ${completedCount}/6` }}</strong></div>
          <el-button type="primary" :loading="action === 'reconciliation'" :disabled="state.paymentStatus !== 'SUCCEEDED' || Boolean(state.reconciliationId)" @click="runReconciliation">执行对账</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workflow-shell{display:grid;gap:12px}.workflow-overview{min-height:132px;padding:22px 24px;display:grid;grid-template-columns:minmax(300px,.75fr) minmax(580px,1.25fr);align-items:center;gap:30px;background:linear-gradient(120deg,#fff 55%,#f0fbf6)}.eyebrow{color:var(--mb-green-dark);font-size:11px;font-weight:800;letter-spacing:1.2px}.overview-copy h2{margin:7px 0 6px;font-size:22px;color:#10203d}.overview-copy p{margin:0;color:var(--mb-muted);font-size:12px}.progress-summary{height:38px;padding:0 12px;display:flex;align-items:center;gap:7px;border:1px solid #bce8d4;border-radius:7px;background:#effbf5;color:var(--mb-green-dark)}.progress-summary strong{font-size:16px}.progress-summary span{font-size:11px}.step-rail{display:grid;grid-template-columns:repeat(6,1fr);position:relative}.step-rail::before{content:"";position:absolute;top:16px;left:8%;right:8%;height:2px;background:#dce5ee}.rail-item{position:relative;z-index:1;display:grid;justify-items:center;gap:6px;color:#8b98ab}.rail-item>span{width:34px;height:34px;display:grid;place-items:center;border:2px solid #dce5ee;border-radius:50%;background:#fff;font-weight:800}.rail-item.done{color:var(--mb-green-dark)}.rail-item.done>span{border-color:var(--mb-green);background:var(--mb-green);color:#fff}.rail-item small{font-weight:700}.workflow-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.flow-card{min-height:410px;display:flex;flex-direction:column;transition:border-color .2s,box-shadow .2s}.flow-card.complete{border-color:#b8e6cf;box-shadow:0 2px 8px rgba(6,158,98,.07)}.flow-card>header{min-height:68px;padding:0 16px;display:grid;grid-template-columns:30px 28px 1fr auto;align-items:center;gap:9px;border-bottom:1px solid var(--mb-soft-line)}.flow-card>header .el-icon{font-size:20px;color:var(--mb-green-dark)}.flow-card h3{margin:0 0 3px;font-size:16px}.flow-card header p{margin:0;color:var(--mb-muted);font-size:11px}.step-no{font:700 12px/1 "Cascadia Mono",monospace;color:#8b98ab}.flow-body{flex:1;padding:15px;display:flex;flex-direction:column;gap:13px}.flow-body.compact{justify-content:space-between}.flow-body>.el-button{align-self:flex-start;margin-top:auto}.form-row,.form-pair>div{display:grid;gap:6px}.form-row label,.form-pair label{font-size:11px;color:#66748e;font-weight:700}.form-row .el-select,.form-pair .el-select,.form-pair .el-input-number{width:100%}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:10px}.selection-note,.result-strip{min-height:38px;padding:8px 10px;display:flex;align-items:center;gap:9px;border-radius:6px;background:#f5f8fb;font-size:11px}.selection-note span,.result-strip span{color:var(--mb-muted)}.selection-note code{margin-left:auto;color:#47607f}.result-strip{background:var(--mb-green-soft);color:var(--mb-green-dark)}.result-strip em{margin-left:auto;font-style:normal}.data-list{display:grid;grid-template-columns:92px minmax(0,1fr);gap:10px 12px;align-items:start;font-size:12px}.data-list span{color:var(--mb-muted)}.data-list strong{font-weight:650;overflow-wrap:anywhere}.read-only{height:32px;padding:7px 11px;border:1px solid var(--mb-line);border-radius:6px;background:#f7f9fc;color:#52627d}.warning-note{padding:10px;border:1px solid #fed7aa;border-radius:7px;background:#fff8ed;display:grid;gap:4px;font-size:11px;color:#9a5500}.warning-note strong{color:#bd6500}.amount-box{display:grid;gap:5px;padding:14px;border:1px solid var(--mb-soft-line);border-radius:7px;background:#f8fafc}.amount-box span,.amount-box small{color:var(--mb-muted);font-size:11px}.amount-box strong{font-size:24px;color:#122143}.reconciliation-result,.empty-result{min-height:84px;padding:14px;border-radius:7px;display:grid;align-content:center;gap:5px}.reconciliation-result{background:#fff7ed;color:#b45309}.reconciliation-result.clean{background:#ecfdf5;color:#087950}.reconciliation-result strong{font-size:20px}.reconciliation-result span,.empty-result{font-size:11px}.empty-result{background:#f7f9fc;color:var(--mb-muted)}
@media (max-width:1450px){.workflow-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.workflow-overview{grid-template-columns:1fr 1.35fr}}@media (max-width:1050px){.workflow-grid{grid-template-columns:1fr}.workflow-overview{grid-template-columns:1fr}.step-rail{margin-top:8px}}
</style>
