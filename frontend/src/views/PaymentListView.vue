<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, unwrap } from '../api/client'
import type { PageResponse } from '../types'
import { useListQuery } from '../composables/useListQuery'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'
interface Payment { id:number; invoiceId:number; externalReference:string; channelCode:string; currency:string; amountMinor:number; status:string; createdAt:string; completedAt:string|null }
const router=useRouter(); const {query,syncQuery}=useListQuery({page:1,size:20,status:''}); const result=ref<PageResponse<Payment>>({page:1,size:20,total:0,records:[]}); const loading=ref(false)
const money=(c:string,v:number)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:c}).format(v/100)
async function load(){loading.value=true;try{await syncQuery();result.value=unwrap(await api.get('/tenant/payments',{params:query}))}finally{loading.value=false}}
function open(row:Payment){router.push(`/finance/payment/${row.id}`)}
onMounted(load)
</script>
<template><MBPageHeader title="支付与退款" subtitle="支付状态完全以服务端和渠道事实为准；UNKNOWN 显示为结果待确认。"/><section class="mb-card table-card"><div class="filter-bar"><el-select v-model="query.status" clearable placeholder="全部状态"><el-option v-for="s in ['PENDING','UNKNOWN','SUCCEEDED','FAILED','REVERSED']" :key="s" :value="s" :label="s"/></el-select><el-button type="primary" @click="query.page=1;load()">查询</el-button></div><el-table v-loading="loading" :data="result.records" @row-click="open"><el-table-column label="支付单" width="130"><template #default="{row}"><a class="link mono">PAY-{{ row.id }}</a></template></el-table-column><el-table-column label="账单" width="120"><template #default="{row}">INV-{{ row.invoiceId }}</template></el-table-column><el-table-column prop="externalReference" label="外部交易号" min-width="240"/><el-table-column prop="channelCode" label="渠道" width="120"/><el-table-column label="金额" width="150"><template #default="{row}"><span class="money">{{ money(row.currency,row.amountMinor) }}</span></template></el-table-column><el-table-column label="状态" width="120"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column><el-table-column prop="createdAt" label="发起时间" min-width="190"/></el-table><div class="pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[10,20,50,100]" :total="result.total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()"/></div></section></template>
