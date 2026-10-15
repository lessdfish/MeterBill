<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, Box, Coin, Connection, CreditCard, DataAnalysis, DataLine, Document, Goods, Histogram, List, Lock, Operation, PriceTag, Refresh, Search, Setting, SwitchButton, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const initials = computed(() => auth.profile?.displayName?.slice(0, 2).toUpperCase() || 'MB')
const periodLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(new Date()))
const groups = computed(() => [
  { title: '', items: [
    { label: '运营概览', to: '/dashboard', icon: Histogram },
    ...(auth.profile?.platformAdmin ? [{ label: '业务闭环', to: '/workflow', icon: Connection }] : []),
  ] },
  { title: '财务中心', items: [
    { label: '账单管理', to: '/finance/invoices', icon: Document },
    { label: '收款与对账', to: '/finance/payments', icon: CreditCard },
    { label: '对账差异', to: '/finance/reconciliation', icon: Refresh },
    { label: '财务报表', to: '/finance/reports', icon: DataAnalysis },
  ] },
  ...(auth.profile?.platformAdmin ? [{ title: '产品与定价', items: [
    { label: '产品目录', to: { path: '/catalog' }, icon: Goods, section: 'catalog' },
    { label: '价格版本', to: { path: '/catalog', query: { section: 'prices' } }, icon: PriceTag, section: 'prices' },
    { label: '计费规则', to: { path: '/catalog', query: { section: 'rules' } }, icon: Coin, section: 'rules' },
  ] }] : []),
  { title: '订阅管理', items: [
    ...(auth.profile?.platformAdmin ? [{ label: '客户管理', to: '/customers', icon: User }] : []),
    { label: '订阅列表', to: '/subscriptions', icon: List },
  ] },
  { title: '用量中心', items: [
    { label: '用量查询', to: '/usage', icon: DataLine },
    { label: '用量汇总', to: '/usage/summary', icon: DataAnalysis },
  ] },
  { title: '运行状态', items: [{ label: '任务处理', to: '/operations', icon: Operation }] },
  { title: '权限与审计', items: [
    { label: '成员与角色', to: '/security', icon: Lock },
    { label: '操作审计', to: '/audit', icon: Document },
    { label: '系统设置', to: '/settings', icon: Setting },
  ] },
])

function isNavActive(item: Record<string, unknown>) {
  if (!item.section) return false
  return route.path === '/catalog' && String(route.query.section || 'catalog') === String(item.section)
}

function navActiveClass(item: Record<string, unknown>) {
  return item.section ? 'nav-query-route' : 'router-link-active'
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><img src="/assets/meterbill-m.png" alt="" /><span>MeterBill</span></div>
      <nav class="nav" aria-label="主导航">
        <div v-for="group in groups" :key="group.title" class="nav-group">
          <div v-if="group.title" class="nav-title">{{ group.title }}</div>
          <router-link v-for="item in group.items" :key="item.label" :to="item.to" class="nav-link" :active-class="navActiveClass(item)" :class="{ selected: isNavActive(item) }">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
          </router-link>
        </div>
      </nav>
    </aside>
    <main class="main">
      <header class="topbar">
        <el-icon><Box /></el-icon>
        <el-select class="tenant-select" :model-value="auth.tenantId" popper-class="tenant-select-popper" @change="auth.selectTenant">
          <el-option v-for="tenant in auth.profile?.tenants" :key="tenant.tenantId" :value="tenant.tenantId" :label="`${tenant.tenantName} · ${tenant.tenantCode}`">
            <span class="tenant-option"><strong>{{ tenant.tenantName }}</strong><small>{{ tenant.tenantCode }}</small></span>
          </el-option>
        </el-select>
        <span class="period-label">账单周期</span><span class="period-pill">{{ periodLabel }}（进行中）</span>
        <el-input class="global-search" :prefix-icon="Search" placeholder="搜索账单、订阅、用量或事件..." />
        <el-button text circle aria-label="通知"><el-icon><Bell /></el-icon></el-button>
        <div class="profile"><span class="avatar">{{ initials }}</span><div class="profile-copy"><strong>{{ auth.profile?.displayName }}</strong><small>{{ auth.profile?.platformAdmin ? '平台管理员' : '租户成员' }}</small></div></div>
        <el-button text circle aria-label="退出登录" @click="auth.logout"><el-icon><SwitchButton /></el-icon></el-button>
      </header>
      <section class="content" :data-page="String(route.meta.title || '')"><router-view /></section>
    </main>
  </div>
</template>
