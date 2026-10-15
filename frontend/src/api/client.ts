import axios from 'axios'
import { ElMessage } from 'element-plus'

export const api = axios.create({ baseURL: '/api/v1', timeout: 15000 })
export const ingestApi = axios.create({ baseURL: '/api/v1', timeout: 15000 })

export class ApiRequestError extends Error {
  constructor(message: string, public readonly status?: number, public readonly code?: string) {
    super(message)
  }
}

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('meterbill.token')
  const tenantId = localStorage.getItem('meterbill.tenantId')
  if (token) config.headers.Authorization = `Bearer ${token}`
  if (tenantId && config.url?.includes('/tenant/')) config.headers['X-Tenant-Id'] = tenantId
  return config
})

api.interceptors.response.use(
  (response) => {
    if (response.data?.code && response.data.code !== 'SUCCESS') {
      return Promise.reject(new ApiRequestError(
        response.data.message || '请求失败', response.status, response.data.code))
    }
    return response
  },
  (error) => {
    const status = error.response?.status
    const code = error.response?.data?.code
    let message = error.response?.data?.message || error.message || '请求失败'
    if (status === 401) {
      localStorage.removeItem('meterbill.token')
      localStorage.removeItem('meterbill.tenantId')
      if (location.pathname !== '/login') location.assign('/login')
    } else {
      if (status === 403) message = '当前账号没有执行此操作的权限'
      else if (status === 409) message = error.response?.data?.message || '数据状态已变化，请刷新后重试'
      else if (status === 503) message = '服务暂时不可用，请稍后重试'
      else if (error.code === 'ECONNABORTED') message = '请求超时，请检查服务状态后重试'
      else if (!error.response) message = '无法连接服务器，请检查网络或后端进程'
      ElMessage.error(message)
    }
    return Promise.reject(new ApiRequestError(message, status, code))
  },
)

ingestApi.interceptors.response.use(
  (response) => {
    if (response.data?.code && response.data.code !== 'SUCCESS') {
      return Promise.reject(new ApiRequestError(
        response.data.message || '请求失败', response.status, response.data.code))
    }
    return response
  },
  (error) => {
    const status = error.response?.status
    const code = error.response?.data?.code
    let message = error.response?.data?.message || error.message || '用量接入请求失败'
    if (status === 401) message = '用量接入密钥无效或已失效'
    else if (status === 409) message = error.response?.data?.message || '用量事件与现有数据冲突'
    else if (error.code === 'ECONNABORTED') message = '用量处理状态查询超时'
    else if (!error.response) message = '无法连接用量接入服务'
    ElMessage.error(message)
    return Promise.reject(new ApiRequestError(message, status, code))
  },
)

export const unwrap = <T>(response: { data: { data: T } }): T => response.data.data
