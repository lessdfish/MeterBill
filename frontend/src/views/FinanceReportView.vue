<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, unwrap } from '../api/client'
import MBPageHeader from '../components/MBPageHeader.vue'
const query=reactive({invoiceId:'',businessType:'',limit:200});const rows=ref<any[]>([]);const loading=ref(false)
const money=(c:string,v:number)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:c}).format(v/100)
async function load(){loading.value=true;try{rows.value=unwrap(await api.get('/tenant/ledger/entries',{params:{invoiceId:query.invoiceId||undefined,businessType:query.businessType||undefined,limit:query.limit}}))}finally{loading.value=false}}
onMounted(load)
</script>
<template><MBPageHeader title="账务分录" subtitle="查询真实双分录流水，也可输入账单 ID 反查调整、支付、退款与冲正。"/><section class="mb-card table-card"><div class="filter-bar"><el-input v-model="query.invoiceId" clearable placeholder="账单 ID"/><el-select v-model="query.businessType" clearable placeholder="全部业务类型"><el-option v-for="t in ['INVOICE','INVOICE_ADJUSTMENT','PAYMENT','REFUND','PAYMENT_REVERSAL']" :key="t" :value="t" :label="t"/></el-select><el-button type="primary" @click="load">查询分录</el-button></div><el-table v-loading="loading" :data="rows"><el-table-column prop="transactionId" label="事务" width="90"/><el-table-column prop="businessType" label="业务类型" min-width="170"/><el-table-column prop="businessId" label="业务ID" width="100"/><el-table-column prop="postingAction" label="动作" width="110"/><el-table-column prop="accountCode" label="科目" width="130"/><el-table-column prop="direction" label="方向" width="90"/><el-table-column label="金额" width="140"><template #default="{row}">{{ money(row.currency,row.amountMinor) }}</template></el-table-column><el-table-column prop="description" label="说明" min-width="180"/><el-table-column prop="createdAt" label="记账时间" min-width="190"/></el-table><el-empty v-if="!loading&&!rows.length" description="没有符合条件的分录"/></section></template>
