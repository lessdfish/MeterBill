<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, unwrap } from '../api/client'
import type { DashboardOverview, PageResponse } from '../types'
import MBCard from '../components/MBCard.vue'
import MBPageHeader from '../components/MBPageHeader.vue'

const loading = ref(false); const overview = ref<DashboardOverview | null>(null); const events = ref<any[]>([])
const totalQuantity = computed(() => events.value.reduce((sum, row) => sum + Number(row.quantity || 0), 0))
const failed = computed(() => events.value.filter(row => ['RETRYABLE_FAILED','FINAL_FAILED'].includes(row.status)).length)
const meterRows = computed(() => Object.entries(events.value.reduce((result: Record<string, { count:number; quantity:number }>, row) => { const key = row.meterCode || '未标识'; const item = result[key] || { count:0, quantity:0 }; item.count++; item.quantity += Number(row.quantity || 0); result[key] = item; return result }, {})).map(([meter, value]) => ({ meter, ...value })).sort((a,b) => b.quantity-a.quantity))
const maxUsage = computed(() => Math.max(1, ...(overview.value?.usageTrend.map(item => item.quantity) || [1])))
async function load(){loading.value=true;try{const [summary,page]=await Promise.all([api.get('/tenant/dashboard/overview').then(r=>unwrap<DashboardOverview>(r)),api.get('/tenant/usage-events',{params:{page:1,size:200}}).then(r=>unwrap<PageResponse<any>>(r))]);overview.value=summary;events.value=page.records}finally{loading.value=false}}
onMounted(load)
</script>

<template>
  <MBPageHeader title="用量汇总" subtitle="汇总当前租户最近用量事件，并展示最近 7 天数据库趋势。"><template #actions><el-button @click="load">刷新汇总</el-button></template></MBPageHeader>
  <div class="kpi-grid" v-loading="loading"><article class="kpi"><span class="kpi-label">事件数量</span><div class="kpi-value">{{ events.length }}</div><div class="kpi-note">当前最多汇总 200 条</div></article><article class="kpi"><span class="kpi-label">用量总和</span><div class="kpi-value">{{ totalQuantity.toLocaleString() }}</div><div class="kpi-note">按事件 quantity 求和</div></article><article class="kpi"><span class="kpi-label">失败事件</span><div class="kpi-value">{{ failed }}</div><div class="kpi-note">可重试与最终失败</div></article><article class="kpi"><span class="kpi-label">计量项</span><div class="kpi-value">{{ meterRows.length }}</div><div class="kpi-note">数据库中的不同 meter</div></article></div>
  <div class="usage-summary-grid"><MBCard title="最近 7 天趋势"><div v-if="overview?.usageTrend.length" class="chart"><div v-for="point in overview.usageTrend" :key="point.date" class="chart-column"><div class="chart-bar" :style="{height:`${Math.max(3,point.quantity/maxUsage*100)}%`}"/><strong>{{ point.quantity.toLocaleString() }}</strong><span>{{ point.date.slice(5) }}</span></div></div><el-empty v-else description="最近 7 天没有用量事件"/></MBCard><MBCard title="按计量项汇总"><el-table :data="meterRows" size="small"><el-table-column prop="meter" label="计量项"/><el-table-column prop="count" label="事件数"/><el-table-column prop="quantity" label="用量"/></el-table><el-empty v-if="!meterRows.length" description="没有可汇总的用量" :image-size="64"/></MBCard></div>
</template>
<style scoped>.usage-summary-grid{display:grid;grid-template-columns:minmax(560px,1.2fr) minmax(380px,.8fr);gap:12px}@media(max-width:1200px){.usage-summary-grid{grid-template-columns:1fr}}</style>
