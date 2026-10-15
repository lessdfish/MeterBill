<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, unwrap } from '../api/client'
import type { DashboardOverview } from '../types'
import type { PageResponse } from '../types'
import MBCard from '../components/MBCard.vue'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'

const loading = ref(true)
const error = ref('')
const data = ref<DashboardOverview | null>(null)
const invoices = ref<any[]>([])
const payments = ref<any[]>([])
const maxUsage = computed(() => Math.max(1, ...(data.value?.usageTrend.map((item) => item.quantity) || [1])))
const money = (value = 0) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: data.value?.currency || 'CNY' }).format(value / 100)
onMounted(async () => {
  try {
    const [overview, invoicePage, paymentPage] = await Promise.all([
      api.get('/tenant/dashboard/overview').then(response => unwrap<DashboardOverview>(response)),
      api.get('/tenant/invoices', { params: { page: 1, size: 5 } }).then(response => unwrap<PageResponse<any>>(response)),
      api.get('/tenant/payments', { params: { page: 1, size: 5 } }).then(response => unwrap<PageResponse<any>>(response)),
    ])
    data.value = overview; invoices.value = invoicePage.records; payments.value = paymentPage.records
  }
  catch (e) { error.value = e instanceof Error ? e.message : '加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <MBPageHeader title="MeterBill Billing Ops" subtitle="当前租户的计量、计费、账单、收款与异常概览" />
  <div v-if="error" class="error-state">{{ error }}</div>
  <template v-else>
    <div class="kpi-grid" v-loading="loading">
      <article class="kpi"><span class="kpi-label">本期应收</span><div class="kpi-value money">{{ money(data?.receivableMinor) }}</div><div class="kpi-note">数据库账单与调整合计</div></article>
      <article class="kpi"><span class="kpi-label">已支付</span><div class="kpi-value money">{{ money(data?.paidMinor) }}</div><div class="kpi-note">成功支付扣除成功退款</div></article>
      <article class="kpi"><span class="kpi-label">待支付</span><div class="kpi-value money">{{ money(data?.outstandingMinor) }}</div><div class="kpi-note">{{ data?.pendingInvoices || 0 }} 张未结清账单</div></article>
      <article class="kpi"><span class="kpi-label">待处理异常</span><div class="kpi-value">{{ (data?.unknownPayments || 0) + (data?.failedPayments || 0) + (data?.failedUsageEvents || 0) + (data?.openDifferences || 0) }}</div><div class="kpi-note">支付、用量与对账真实状态</div></article>
    </div>
    <div class="two-col">
      <MBCard title="用量趋势（最近 7 天）">
        <div v-if="data?.usageTrend.length" class="chart">
          <div v-for="point in data.usageTrend" :key="point.date" class="chart-column"><div class="chart-bar" :style="{ height: `${Math.max(3, point.quantity / maxUsage * 100)}%` }" /><strong>{{ point.quantity.toLocaleString() }}</strong><span>{{ point.date.slice(5) }}</span></div>
        </div><el-empty v-else description="当前租户最近 7 天没有用量事件" />
      </MBCard>
      <MBCard title="账务健康度">
        <dl class="health-list">
          <div><dt>结果待确认支付</dt><dd class="warning">{{ data?.unknownPayments || 0 }}</dd></div>
          <div><dt>支付失败</dt><dd class="danger">{{ data?.failedPayments || 0 }}</dd></div>
          <div><dt>失败用量事件</dt><dd class="danger">{{ data?.failedUsageEvents || 0 }}</dd></div>
          <div><dt>开放对账差异</dt><dd class="warning">{{ data?.openDifferences || 0 }}</dd></div>
        </dl>
      </MBCard>
    </div>
    <div class="dashboard-lower">
      <MBCard title="最近账单"><el-table :data="invoices" size="small"><el-table-column label="账单"><template #default="{row}"><router-link class="link mono" to="/finance/invoices">INV-{{ row.id }}</router-link></template></el-table-column><el-table-column prop="subscriptionId" label="订阅"/><el-table-column label="应收"><template #default="{row}">{{ money(row.totalAmountMinor) }}</template></el-table-column><el-table-column label="状态"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column></el-table><el-empty v-if="!invoices.length" description="当前租户没有账单" :image-size="56"/></MBCard>
      <MBCard title="最近支付"><el-table :data="payments" size="small"><el-table-column label="支付单"><template #default="{row}"><router-link class="link mono" :to="`/finance/payment/${row.id}`">PAY-{{ row.id }}</router-link></template></el-table-column><el-table-column prop="channelCode" label="渠道"/><el-table-column label="金额"><template #default="{row}">{{ money(row.amountMinor) }}</template></el-table-column><el-table-column label="状态"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column></el-table><el-empty v-if="!payments.length" description="当前租户没有支付记录" :image-size="56"/></MBCard>
    </div>
  </template>
</template>

<style scoped>.two-col{min-height:365px}.health-list { margin: 0; display: grid; gap: 0; }.health-list>div { min-height: 72px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--mb-soft-line); }.health-list>div:last-child { border: 0; }.health-list dt { color: var(--mb-muted); }.health-list dd { margin: 0; font-size: 22px; font-weight: 700; }.warning { color: var(--mb-orange); }.danger { color: var(--mb-red); }.dashboard-lower{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px;min-height:260px}.dashboard-lower :deep(.mb-card){height:100%}@media(min-height:1100px){.two-col{min-height:420px}.dashboard-lower{min-height:520px}.chart{height:330px}}</style>
