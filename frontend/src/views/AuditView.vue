<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, unwrap } from '../api/client'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'

const loading=ref(false); const rows=ref<any[]>([]); const keyword=ref(''); const outcome=ref('')
const filtered=computed(()=>rows.value.filter(row=>(!outcome.value||row.outcome===outcome.value)&&(!keyword.value||[row.action,row.targetType,row.targetId,row.requestId].some(value=>String(value||'').toLowerCase().includes(keyword.value.toLowerCase())))))
const date=(value?:string)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'
async function load(){loading.value=true;try{rows.value=unwrap(await api.get('/tenant/security/audit',{params:{limit:100}}))}finally{loading.value=false}}
onMounted(load)
</script>
<template><MBPageHeader title="操作审计" subtitle="查询当前租户最近 100 条真实安全审计记录；记录只读且不可在界面删除。"><template #actions><el-button @click="load">刷新记录</el-button></template></MBPageHeader><section class="mb-card table-card"><div class="filter-bar"><el-input v-model="keyword" clearable placeholder="操作、对象或请求ID"/><el-select v-model="outcome" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILURE"/></el-select><span class="audit-count">显示 {{ filtered.length }} / {{ rows.length }} 条</span></div><el-table v-loading="loading" :data="filtered"><el-table-column label="时间" min-width="180"><template #default="{row}">{{ date(row.createdAt) }}</template></el-table-column><el-table-column prop="actorType" label="操作者类型" width="120"/><el-table-column prop="actorId" label="操作者" width="100"/><el-table-column prop="action" label="操作" min-width="180"/><el-table-column prop="targetType" label="对象类型" width="120"/><el-table-column prop="targetId" label="对象ID" min-width="130"/><el-table-column prop="requestId" label="请求ID" min-width="210" show-overflow-tooltip/><el-table-column label="结果" width="100"><template #default="{row}"><MBStatusTag :status="row.outcome==='SUCCESS'?'SUCCEEDED':'FAILED'"/></template></el-table-column></el-table><el-empty v-if="!loading&&!filtered.length" description="没有符合条件的审计记录" :image-size="64"/></section></template>
<style scoped>.audit-count{margin-left:auto;color:var(--mb-muted);font-size:12px}</style>
