<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, unwrap } from '../api/client'
import type { PageResponse } from '../types'
import { useListQuery } from '../composables/useListQuery'
import MBPageHeader from '../components/MBPageHeader.vue'
import MBStatusTag from '../components/MBStatusTag.vue'

const { query, syncQuery } = useListQuery({ page: 1, size: 20, status: '', keyword: '' })
const result = ref<PageResponse<any>>({ page: 1, size: 20, total: 0, records: [] })
const loading = ref(false)
const money = (value = 0, currency = 'CNY') => new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value / 100)
const date = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
async function load() { loading.value = true; try { await syncQuery(); result.value = unwrap(await api.get('/platform/customers', { params: query })) } finally { loading.value = false } }
onMounted(load)
</script>

<template>
  <MBPageHeader title="客户管理" subtitle="客户对应计费租户；成员、有效订阅、账单和待收金额均来自数据库实时聚合。" />
  <section class="mb-card table-card">
    <div class="filter-bar"><el-input v-model="query.keyword" clearable placeholder="客户名称或租户编码" @keyup.enter="query.page=1;load()"/><el-select v-model="query.status" clearable placeholder="全部状态"><el-option label="启用" value="ACTIVE"/><el-option label="暂停" value="SUSPENDED"/><el-option label="关闭" value="CLOSED"/></el-select><el-button type="primary" @click="query.page=1;load()">查询</el-button></div>
    <el-table v-loading="loading" :data="result.records"><el-table-column prop="tenantName" label="客户名称" min-width="180"/><el-table-column prop="tenantCode" label="租户编码" min-width="190"/><el-table-column label="状态" width="100"><template #default="{ row }"><MBStatusTag :status="row.status"/></template></el-table-column><el-table-column prop="memberCount" label="成员" width="90"/><el-table-column prop="activeSubscriptionCount" label="有效订阅" width="100"/><el-table-column prop="invoiceCount" label="账单" width="90"/><el-table-column label="待收金额" min-width="140"><template #default="{ row }"><span class="money">{{ money(row.outstandingMinor, row.currency) }}</span></template></el-table-column><el-table-column label="创建时间" min-width="190"><template #default="{ row }">{{ date(row.createdAt) }}</template></el-table-column></el-table>
    <div class="pagination"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[10,20,50,100]" :total="result.total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.page=1;load()"/></div>
  </section>
</template>
