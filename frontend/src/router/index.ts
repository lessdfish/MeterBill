import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    {
      path: '/', component: () => import('../layouts/AppLayout.vue'), redirect: '/dashboard', children: [
        { path: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '运营概览' } },
        { path: 'workflow', component: () => import('../views/BillingWorkflowView.vue'), meta: { title: '业务闭环', platform: true } },
        { path: 'finance/invoices', component: () => import('../views/InvoiceView.vue'), meta: { title: '账单中心' } },
        { path: 'finance/payments', component: () => import('../views/PaymentListView.vue'), meta: { title: '支付与退款' } },
        { path: 'finance/payment/:id', component: () => import('../views/PaymentInvestigationView.vue'), meta: { title: '支付调查' } },
        { path: 'finance/reconciliation', component: () => import('../views/ReconciliationView.vue'), meta: { title: '对账中心' } },
        { path: 'finance/reports', component: () => import('../views/FinanceReportView.vue'), meta: { title: '财务报表' } },
        { path: 'catalog', component: () => import('../views/ProductView.vue'), meta: { title: '产品与定价', platform: true } },
        { path: 'customers', component: () => import('../views/CustomerView.vue'), meta: { title: '客户管理', platform: true } },
        { path: 'subscriptions', component: () => import('../views/SubscriptionView.vue'), meta: { title: '订阅管理' } },
        { path: 'usage', component: () => import('../views/UsageView.vue'), meta: { title: '用量记录' } },
        { path: 'usage/summary', component: () => import('../views/UsageSummaryView.vue'), meta: { title: '用量汇总' } },
        { path: 'operations', component: () => import('../views/OperationsView.vue'), meta: { title: '异常任务' } },
        { path: 'security', component: () => import('../views/SecurityView.vue'), meta: { title: '成员与审计' } },
        { path: 'audit', component: () => import('../views/AuditView.vue'), meta: { title: '操作审计' } },
        { path: 'settings', component: () => import('../views/SystemSettingsView.vue'), meta: { title: '系统设置' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return auth.authenticated ? '/dashboard' : true
  if (!auth.authenticated) return '/login'
  if (!auth.profile) {
    try { await auth.loadProfile() } catch { return '/login' }
  }
  if (to.meta.platform && !auth.profile?.platformAdmin) return '/dashboard'
  return true
})

export default router
