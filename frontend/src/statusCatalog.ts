export type StatusKind = 'success' | 'failed' | 'pending' | 'unknown' | 'neutral'

export interface StatusMeta {
  label: string
  kind: StatusKind
  icon: string
  consequence: string
}

const statusCatalog: Record<string, StatusMeta> = {
  PENDING: { label: '处理中', kind: 'pending', icon: '◷', consequence: '尚未完成，请等待结果后再进行后续操作。' },
  ACTIVE: { label: '有效', kind: 'success', icon: '✓', consequence: '当前配置或订阅正在生效。' },
  SUSPENDED: { label: '已暂停', kind: 'pending', icon: 'Ⅱ', consequence: '当前对象暂时停止处理新业务，恢复后可继续使用。' },
  CLOSED: { label: '已关闭', kind: 'neutral', icon: '—', consequence: '当前对象已关闭，不再参与新的业务处理。' },
  DISABLED: { label: '已禁用', kind: 'neutral', icon: '—', consequence: '当前凭据或功能不可继续使用。' },
  INVITED: { label: '待接受邀请', kind: 'pending', icon: '✉', consequence: '成员尚未接受邀请，权限还未生效。' },
  CANCEL_SCHEDULED: { label: '待取消', kind: 'pending', icon: '◷', consequence: '当前周期仍有效，到期后停止续订。' },
  CANCELED: { label: '已取消', kind: 'neutral', icon: '—', consequence: '不会继续产生新的账期或业务处理。' },
  EXPIRED: { label: '已到期', kind: 'neutral', icon: '—', consequence: '需要续订后才能继续使用。' },
  RECEIVED: { label: '已接收', kind: 'pending', icon: '↓', consequence: '事件已可靠入库，等待异步累计。' },
  CREATED: { label: '已创建', kind: 'pending', icon: '+', consequence: '记录已创建，等待后续流程处理。' },
  SCHEDULED: { label: '已计划', kind: 'pending', icon: '◷', consequence: '任务已排期，将在指定条件满足后执行。' },
  RUNNING: { label: '执行中', kind: 'pending', icon: '…', consequence: '任务正在执行，请等待最终结果。' },
  READY: { label: '待处理', kind: 'pending', icon: '◷', consequence: '任务已就绪，等待后台处理。' },
  PUBLISHING: { label: '发送中', kind: 'pending', icon: '↑', consequence: '消息正在发送，暂时无需人工操作。' },
  PROCESSED: { label: '已完成', kind: 'success', icon: '✓', consequence: '事件已完成累计，不会再次计量。' },
  POSTED: { label: '已入账', kind: 'success', icon: '✓', consequence: '账务分录已经生效，不能直接覆盖。' },
  APPLIED: { label: '已应用', kind: 'success', icon: '✓', consequence: '调整已经计入目标账单或余额。' },
  RETRYABLE_FAILED: { label: '等待重试', kind: 'pending', icon: '↻', consequence: '本次处理失败，系统会自动重试。' },
  FINAL_FAILED: { label: '最终失败', kind: 'failed', icon: '×', consequence: '自动重试已停止，需要人工排查并重试。' },
  DRAFT: { label: '草稿', kind: 'neutral', icon: '✎', consequence: '尚未正式生效，可以继续编辑。' },
  ISSUED: { label: '待支付', kind: 'pending', icon: '◷', consequence: '账单已正式出具，仍有应付金额。' },
  PARTIALLY_PAID: { label: '部分支付', kind: 'pending', icon: '◐', consequence: '仍有未付余额，需要继续支付。' },
  PAID: { label: '已支付', kind: 'success', icon: '✓', consequence: '应付金额已经结清。' },
  VOIDED: { label: '已作废', kind: 'failed', icon: '×', consequence: '账单不再允许支付。' },
  WAIVED: { label: '已豁免', kind: 'neutral', icon: '—', consequence: '该金额已按规则豁免，不再要求支付。' },
  SUCCEEDED: { label: '成功', kind: 'success', icon: '✓', consequence: '业务已经成功完成，不需要重复提交。' },
  FAILED: { label: '失败', kind: 'failed', icon: '×', consequence: '本次操作未成功，可核查原因后重试。' },
  UNKNOWN: { label: '结果待确认', kind: 'unknown', icon: '?', consequence: '不能判断成功或失败，请先主动查询渠道。' },
  REVOKED: { label: '支付已撤销', kind: 'failed', icon: '↶', consequence: '原支付已撤销，账单未付余额已经恢复。' },
  PUBLISHED: { label: '已发布', kind: 'success', icon: '✓', consequence: '当前版本已经生效，历史内容不可直接覆盖。' },
  COMPLETED: { label: '已完成', kind: 'success', icon: '✓', consequence: '流程已经完成。' },
  OPEN: { label: '存在差异', kind: 'failed', icon: '!', consequence: '对账存在未处理差异，需要调查。' },
  INVESTIGATING: { label: '调查中', kind: 'pending', icon: '…', consequence: '差异正在调查，尚不能关闭。' },
  RESOLVED: { label: '对账一致', kind: 'success', icon: '✓', consequence: '差异已经解决或当前没有差异。' },
  INACTIVE: { label: '停用', kind: 'neutral', icon: '—', consequence: '当前对象不会参与新的业务处理。' },
  RETIRED: { label: '已停用', kind: 'neutral', icon: '—', consequence: '当前版本不再用于新的订阅或计费。' },
  ENDED: { label: '已结束', kind: 'neutral', icon: '—', consequence: '当前周期或流程已经结束。' },
  ARCHIVED: { label: '已归档', kind: 'neutral', icon: '▣', consequence: '记录仅供历史查询，不再参与当前流程。' },
}

export function statusMeta(status?: string | null): StatusMeta & { code: string } {
  const code = (status || 'UNKNOWN').toUpperCase()
  return { code, ...(statusCatalog[code] || { label: code, kind: 'neutral', icon: '•', consequence: '请查看详情确认当前状态影响。' }) }
}
