export interface ApiResponse<T> { code: string; message: string; data: T }
export interface PageResponse<T> { page: number; size: number; total: number; records: T[] }
export interface TenantInfo { tenantId: number; tenantCode: string; tenantName: string }
export interface UserProfile {
  userId: number; email: string; displayName: string; platformAdmin: boolean; tenants: TenantInfo[]
}

export interface DashboardOverview {
  currency: string; receivableMinor: number; paidMinor: number; outstandingMinor: number
  unknownPayments: number; failedPayments: number; failedUsageEvents: number
  openDifferences: number; pendingInvoices: number
  usageTrend: Array<{ date: string; quantity: number; eventCount: number }>
}
