import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, unwrap } from '../api/client'
import type { UserProfile } from '../types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('meterbill.token') || '')
  const profile = ref<UserProfile | null>(null)
  const tenantId = ref(Number(localStorage.getItem('meterbill.tenantId') || 0))
  const tenantRoles = ref<string[]>([])
  const permissions = ref<string[]>([])
  const authenticated = computed(() => Boolean(token.value))
  const currentTenant = computed(() => profile.value?.tenants.find((item) => item.tenantId === tenantId.value))

  async function login(email: string, password: string) {
    const result = unwrap<{ accessToken: string }>(await api.post('/auth/login', { email, password }))
    token.value = result.accessToken
    localStorage.setItem('meterbill.token', token.value)
    await loadProfile()
  }

  async function loadProfile() {
    profile.value = unwrap<UserProfile>(await api.get('/auth/me'))
    if (!tenantId.value || !profile.value.tenants.some((item) => item.tenantId === tenantId.value)) {
      tenantId.value = profile.value.tenants[0]?.tenantId || 0
      if (tenantId.value) localStorage.setItem('meterbill.tenantId', String(tenantId.value))
    }
    if (tenantId.value) await loadTenantSecurity()
  }

  async function loadTenantSecurity() {
    const data = unwrap<{ roles: string[]; permissions: string[] }>(await api.get('/tenant/security/me'))
    tenantRoles.value = data.roles
    permissions.value = data.permissions
  }

  function hasPermission(permission: string) {
    return Boolean(profile.value?.platformAdmin || permissions.value.includes(permission))
  }

  function selectTenant(id: number) {
    tenantId.value = id
    localStorage.setItem('meterbill.tenantId', String(id))
    location.reload()
  }

  async function logout() {
    try { await api.post('/auth/logout') } finally {
      token.value = ''
      profile.value = null
      localStorage.removeItem('meterbill.token')
      localStorage.removeItem('meterbill.tenantId')
      location.assign('/login')
    }
  }

  return { token, profile, tenantId, tenantRoles, permissions, authenticated, currentTenant,
    login, loadProfile, loadTenantSecurity, hasPermission, selectTenant, logout }
})
