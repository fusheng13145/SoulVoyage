/** 任务管线与状态：与后端 PipelineRegistry / TaskInstanceEntity.status 保持一致 */
export const PIPELINES = [
  'DIARY_PIPELINE',
  'COGNITIVE_PIPELINE',
  'SIMULATE_PIPELINE',
  'COMPANION_PIPELINE',
  'GROWTH_LETTER_PIPELINE',
] as const

export type PipelineCode = (typeof PIPELINES)[number]

export const PIPELINE_LABELS: Record<string, string> = {
  DIARY_PIPELINE: '日记梳理',
  COGNITIVE_PIPELINE: '认知书写追问',
  SIMULATE_PIPELINE: '沟通复盘',
  COMPANION_PIPELINE: '漫聊段落消化',
  GROWTH_LETTER_PIPELINE: '成长来信',
}

export const TASK_STATUSES = ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'RUNNING', 'PENDING'] as const

export type TaskStatus = (typeof TASK_STATUSES)[number]

export const TASK_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '已完成',
  PARTIAL_SUCCESS: '部分完成',
  FAILED: '失败',
  RUNNING: '进行中',
  PENDING: '排队中',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

/** 步骤时间线的 Agent 展示名（H-01） */
export const AGENT_LABELS: Record<string, string> = {
  ORCHESTRATOR: '编排',
  EMOTION: '情绪识别',
  TRACE: '认知溯源',
  SIMULATE: '沟通复盘',
  SUPPORT: '支持建议',
  RISK_ARCHIVE: '风险归档',
  COMPANION: '漫聊消化',
  LETTER: '成长来信',
}

/** 终态：SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELLED / EXPIRED（与轮询降级判断同源） */
export const TERMINAL_TASK_STATUSES = [
  'SUCCESS',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
] as const
