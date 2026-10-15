<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, unwrap } from '../api/client'
import type { PageResponse } from '../types'
import { useListQuery } from '../composables/useListQuery'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'
interface Plan { id:number; productName:string; planCode:string; planName:string }
const {query,syncQuery}=useListQuery({page:1,size:20,status:''})
const result=ref<PageResponse<any>>({page:1,size:20,total:0,records:[]})
const plans=ref<Plan[]>([]); const loading=ref(false); const action=ref(false)
const changeVisible=ref(false); const selected=ref<any>(); const changeForm=reactive({planId:null as number|null})
const money=(c:string,v:number)=>c&&v!=null?new Intl.NumberFormat('zh-CN',{style:'currency',currency:c}).format(v/100):'—'
const date=(v:string)=>v?new Date(v).toLocaleString('zh-CN'):'—'
async function load(){loading.value=true;try{await syncQuery();const [subs,catalog]=await Promise.all([api.get('/tenant/subscriptions',{params:query}),api.get('/tenant/catalog/plans')]);result.value=unwrap(subs);plans.value=unwrap(catalog)}finally{loading.value=false}}
async function renew(row:any){await ElMessageBox.confirm('续订会创建下一个自然账期，确认继续？','确认续订');action.value=true;try{await api.post(`/tenant/subscriptions/${row.id}/renew`);ElMessage.success('续订成功');await load()}finally{action.value=false}}
async function cancelAtEnd(row:any){await ElMessageBox.confirm('当前账期结束后订阅将取消，账期内服务不受影响。','期末取消',{type:'warning'});action.value=true;try{await api.post(`/tenant/subscriptions/${row.id}/cancel`,null,{params:{immediate:false}});ElMessage.success('已安排期末取消');await load()}finally{action.value=false}}
function openChange(row:any){selected.value=row;changeForm.planId=null;changeVisible.value=true}
async function saveChange(){if(!changeForm.planId)return ElMessage.warning('请选择新套餐');action.value=true;try{await api.post(`/tenant/subscriptions/${selected.value.id}/plan-changes`,{planId:changeForm.planId},{headers:{'Idempotency-Key':crypto.randomUUID()}});changeVisible.value=false;ElMessage.success('下周期套餐变更已安排');await load()}finally{action.value=false}}
onMounted(load)
</script>
<template><MBPageHeader title="订阅管理" subtitle="续订、期末取消和套餐变更均调用真实订阅状态机。"/><section class="mb-card table-card"><div class="filter-bar"><el-select v-model="query.status" clearable placeholder="全部状态"><el-option v-for="s in ['PENDING','ACTIVE','CANCEL_SCHEDULED','EXPIRED','CANCELED']" :key="s" :value="s" :label="s"/></el-select><el-button type="primary" @click="query.page=1;load()">查询</el-button></div><el-table v-loading="loading" :data="result.records"><el-table-column prop="id" label="订阅ID" width="100"/><el-table-column prop="planName" label="套餐" min-width="170"/><el-table-column label="基础金额" width="140"><template #default="{row}">{{ money(row.currency,row.baseAmountMinor) }}</template></el-table-column><el-table-column label="当前账期" min-width="270"><template #default="{row}">{{ date(row.periodStart) }} — {{ date(row.periodEnd) }}</template></el-table-column><el-table-column label="状态" width="130"><template #default="{row}"><MBStatusTag :status="row.status"/></template></el-table-column><el-table-column label="深度操作" width="250" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="action||!['EXPIRED','CANCELED'].includes(row.status)" @click="renew(row)">续订</el-button><el-button link type="warning" :disabled="action||row.status!=='ACTIVE'" @click="cancelAtEnd(row)">期末取消</el-button><el-button link type="primary" :disabled="action||row.status!=='ACTIVE'" @click="openChange(row)">下周期换套餐</el-button></template></el-table-column></el-table><div class="pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="result.total" layout="total, prev, pager, next" @current-change="load"/></div></section><el-dialog v-model="changeVisible" title="下周期套餐变更" width="520px"><el-alert title="变更仅在下一个账期生效" type="info" :closable="false"/><el-form label-position="top" style="margin-top:18px"><el-form-item label="目标套餐"><el-select v-model="changeForm.planId" filterable style="width:100%"><el-option v-for="p in plans" :key="p.id" :value="p.id" :label="`${p.productName} / ${p.planName} (${p.planCode})`"/></el-select></el-form-item></el-form><template #footer><el-button @click="changeVisible=false">取消</el-button><el-button type="primary" :loading="action" @click="saveChange">确认安排</el-button></template></el-dialog></template>
