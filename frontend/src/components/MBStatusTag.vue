<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ status?: string | null }>()
const normalized = computed(() => (props.status || 'UNKNOWN').toUpperCase())
const kind = computed(() => {
  if (['SUCCEEDED', 'PAID', 'ACTIVE', 'COMPLETED', 'PROCESSED', 'PUBLISHED', 'RESOLVED'].includes(normalized.value)) return 'success'
  if (['FAILED', 'FINAL_FAILED', 'VOIDED'].includes(normalized.value)) return 'failed'
  if (normalized.value === 'UNKNOWN') return 'unknown'
  if (['PENDING', 'ISSUED', 'PARTIALLY_PAID', 'READY', 'RETRYABLE_FAILED', 'INVESTIGATING', 'OPEN'].includes(normalized.value)) return 'pending'
  return ''
})
const label = computed(() => ({
  SUCCEEDED: '成功', PAID: '已支付', ACTIVE: '有效', COMPLETED: '已完成', PROCESSED: '已处理', PUBLISHED: '已发布', RESOLVED: '已解决',
  FAILED: '失败', FINAL_FAILED: '最终失败', VOIDED: '已作废', UNKNOWN: '结果待确认', PENDING: '处理中', ISSUED: '待支付',
  PARTIALLY_PAID: '部分支付', READY: '待处理', RETRYABLE_FAILED: '等待重试', INVESTIGATING: '调查中', OPEN: '待处理',
  REVERSED: '已冲正', CANCELED: '已取消', CANCEL_SCHEDULED: '待取消', EXPIRED: '已到期', DRAFT: '草稿', INACTIVE: '停用', RETIRED: '已停用',
}[normalized.value] || normalized.value))
</script>

<template><span class="status-tag" :class="kind" :title="normalized">{{ label }}</span></template>
