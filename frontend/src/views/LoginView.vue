<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, Message } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
async function submit() {
  loading.value = true; error.value = ''
  try { await auth.login(email.value, password.value); await router.push('/dashboard') }
  catch (e) { error.value = e instanceof Error ? e.message : '登录失败' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="login-brand"><img src="/assets/meterbill-m.png" alt="" /><strong>MeterBill</strong></div>
      <h1>计量计费运营台</h1><p>登录后查看当前数据库中的租户、用量、账单和支付事实。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="邮箱"><el-input v-model="email" :prefix-icon="Message" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" @keyup.enter="submit" /></el-form-item>
        <el-button type="primary" size="large" :loading="loading" :disabled="!email || !password" @click="submit">登录</el-button>
      </el-form>
      <small>此页面不包含演示数据，所有业务状态以服务端返回为准。</small>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; place-items: center; padding: 40px; background: radial-gradient(circle at 70% 10%, #e3f7ee 0, transparent 32%), #f3f7f6; }
.login-panel { width: 420px; padding: 38px; border: 1px solid var(--mb-line); border-radius: 12px; background: rgba(255,255,255,.96); box-shadow: 0 24px 70px rgba(22,62,48,.12); }.login-brand { display: flex; align-items: center; gap: 9px; font-size: 23px; }.login-brand img { width: 32px; }.login-panel h1 { margin: 30px 0 8px; font-size: 26px; }.login-panel p { color: var(--mb-muted); line-height: 1.7; margin-bottom: 24px; }.login-panel .el-button { width: 100%; }.login-panel small { display: block; margin-top: 22px; color: var(--mb-muted); line-height: 1.6; }
</style>
