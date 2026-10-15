<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, unwrap } from '../api/client'
import { useListQuery } from '../composables/useListQuery'
import type { PageResponse } from '../types'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'
import { useAuthStore } from '../stores/auth'

interface InvoiceRow { id: number; subscriptionId: number; tenantName: string; currency: string; periodStart: string; periodEnd: string; totalAmountMinor: number; paidAmountMinor: number; status: string; issuedAt: string }
interface PaymentRow { id:number; invoiceId:number; externalReference:string; channelCode:string; currency:string; amountMinor:number; status:string; createdAt:string }
const { query, syncQuery } = useListQuery({ page: 1, size: 20, status: '', keyword: '' })
const router = useRouter()
const auth = useAuthStore()
const result = ref<PageResponse<InvoiceRow>>({ page: 1, size: 20, total: 0, records: [] })
const loading = ref(false)
const detailLoading = ref(false)
const drawer = ref(false)
const detail = ref<any>(null)
const detailRow = ref<InvoiceRow | null>(null)
const payments = ref<PaymentRow[]>([])
const ledger = ref<any[]>([])
const adjusting = ref(false)
const money = (currency: string, value?: number | null) => value == null ? '—' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value / 100)
const date = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'

async function load() {
  loading.value = true
  try {
    await syncQuery()
    result.value = unwrap(await api.get('/tenant/invoices', { params: query }))
  } finally { loading.value = false }
}
async function open(row: InvoiceRow) {
  drawer.value = true
  detailLoading.value = true
  detailRow.value = row
  try {
    const [invoice, paymentPage, ledgerRows] = await Promise.all([
      api.get(`/tenant/invoices/${row.id}`).then(response => unwrap<any>(response)),
      api.get('/tenant/payments', { params: { page: 1, size: 100, invoiceId: row.id } })
        .then(response => unwrap<PageResponse<PaymentRow>>(response)),
      api.get('/tenant/ledger/entries', { params: { invoiceId: row.id, limit: 200 } })
        .then(response => unwrap<any[]>(response)),
    ])
    detail.value = invoice
    payments.value = paymentPage.records
    ledger.value = ledgerRows
  } finally { detailLoading.value = false }
}
function openPayment(row: PaymentRow) { router.push(`/finance/payment/${row.id}`) }
async function adjustLateUsage() {
  if (!detail.value?.periodId) return
  adjusting.value = true
  try {
    await api.post(`/platform/billing/periods/${detail.value.periodId}/adjust-late-usage`)
    await open(detailRow.value!)
  } finally { adjusting.value = false }
}
onMounted(load)
</script>

<template>
  <MBPageHeader title="账单中心" subtitle="历史账单只读；纠错通过调整、退款或对账流程完成。" />
  <section class="mb-card table-card">
    <div class="filter-bar">
      <el-input v-model="query.keyword" clearable placeholder="账单号或订阅号" @keyup.enter="query.page=1;load()" />
      <el-select v-model="query.status" clearable placeholder="全部状态"><el-option v-for="item in ['ISSUED','PARTIALLY_PAID','PAID','VOIDED']" :key="item" :value="item" :label="item" /></el-select>
      <el-button type="primary" @click="query.page=1;load()">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="result.records" @row-click="open">
      <el-table-column label="账单号" min-width="120"><template #default="{row}"><a class="link mono">INV-{{ row.id }}</a></template></el-table-column>
      <el-table-column prop="tenantName" label="客户名称" min-width="150" /><el-table-column prop="subscriptionId" label="订阅" width="100" />
      <el-table-column label="账单周期" min-width="210"><template #default="{row}">{{ date(row.periodStart) }} — {{ date(row.periodEnd) }}</template></el-table-column>
      <el-table-column label="应收金额" width="140"><template #default="{row}"><span class="money">{{ money(row.currency,row.totalAmountMinor) }}</span></template></el-table-column>
      <el-table-column label="已支付" width="140"><template #default="{row}">{{ money(row.currency,row.paidAmountMinor) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{row}"><MBStatusTag :status="row.status" /></template></el-table-column>
      <el-table-column label="开票日期" min-width="170"><template #default="{row}">{{ date(row.issuedAt) }}</template></el-table-column>
    </el-table>
    <div class="pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[10,20,50,100]" :total="result.total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()" /></div>
  </section>

  <el-drawer v-model="drawer" size="min(680px, 94vw)" title="账单详情">
    <div v-loading="detailLoading" class="invoice-detail">
      <template v-if="detail">
        <div class="drawer-heading"><div><h2 class="mono">INV-{{ detail.id }}</h2><MBStatusTag :status="detail.status" /></div><div><el-button v-if="auth.profile?.platformAdmin" type="primary" plain :loading="adjusting" @click="adjustLateUsage">生成迟到用量调整单</el-button><el-tooltip content="后端当前没有真实 PDF 文件或生成接口"><span><el-button disabled>下载账单 PDF</el-button></span></el-tooltip></div></div>
        <el-descriptions :column="2" border class="detail-block">
          <el-descriptions-item label="订阅">SUB-{{ detail.subscriptionId }}</el-descriptions-item><el-descriptions-item label="账单状态"><MBStatusTag :status="detail.status" /></el-descriptions-item>
          <el-descriptions-item label="账期" :span="2">{{ date(detail.periodStart) }} — {{ date(detail.periodEnd) }}</el-descriptions-item>
          <el-descriptions-item label="基础金额">{{ money(detail.currency,detail.baseAmountMinor) }}</el-descriptions-item><el-descriptions-item label="用量金额">{{ money(detail.currency,detail.usageAmountMinor) }}</el-descriptions-item>
          <el-descriptions-item label="账单合计"><strong>{{ money(detail.currency,detail.totalAmountMinor) }}</strong></el-descriptions-item><el-descriptions-item label="已支付">{{ money(detail.currency,detailRow?.paidAmountMinor) }}</el-descriptions-item>
        </el-descriptions>
        <h3>费用明细</h3>
        <el-table :data="detail.lines" size="small"><el-table-column prop="description" label="项目" min-width="190"/><el-table-column prop="quantity" label="用量" width="90"/><el-table-column label="金额" width="120"><template #default="{row}">{{ money(detail.currency,row.amountMinor) }}</template></el-table-column></el-table>
        <h3>调整记录</h3>
        <el-empty v-if="!detail.adjustments?.length" description="无调整记录" :image-size="54" />
        <el-table v-else :data="detail.adjustments" size="small"><el-table-column prop="adjustmentNo" label="序号" width="70"/><el-table-column prop="reason" label="原因"/><el-table-column label="金额" width="120"><template #default="{row}">{{ money(detail.currency,row.amountMinor) }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column></el-table>
        <h3>支付记录</h3>
        <el-empty v-if="!payments.length" description="该账单暂无支付记录" :image-size="54" />
        <el-table v-else :data="payments" size="small" @row-click="openPayment"><el-table-column label="支付单" width="100"><template #default="{row}"><span class="link mono">PAY-{{ row.id }}</span></template></el-table-column><el-table-column prop="channelCode" label="渠道"/><el-table-column label="金额" width="120"><template #default="{row}">{{ money(row.currency,row.amountMinor) }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column></el-table>
        <h3>账务分录</h3>
        <el-empty v-if="!ledger.length" description="该账单暂无分录" :image-size="54" />
        <el-table v-else :data="ledger" size="small"><el-table-column prop="businessType" label="业务类型" min-width="150"/><el-table-column prop="accountCode" label="科目" width="110"/><el-table-column prop="direction" label="方向" width="80"/><el-table-column label="金额" width="120"><template #default="{row}">{{ money(row.currency,row.amountMinor) }}</template></el-table-column></el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.invoice-detail{min-height:420px}.detail-block{margin:22px 0}.drawer-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.drawer-heading h2{margin:0 10px 0 0;display:inline-block}.invoice-detail h3{margin:26px 0 10px;color:#17294b;font-size:15px}
</style>
