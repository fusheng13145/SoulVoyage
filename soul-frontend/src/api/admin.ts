import http, { type ApiResp } from '@/api/http'
import type { components } from '@/api/schema'

/** M9 管理端接口封装（A1–A5 / O3）：类型对齐后端 admin 控制器的元数据视图。
 *  请求体类型以 OpenAPI 生成物为真源（npm run gen:api），后端契约一变 vue-tsc 即红。 */

/** 编译期契约钉：B 必须可赋给生成 schema 中的请求体 */
type Body<B extends object, K extends keyof components['schemas']> = B & [B] extends [
  components['schemas'][K],
]
  ? unknown
  : never

export interface PageResp<T> {
  items: T[]
  page: number
  size: number
  total: number
}

// —— O3 指标总览 ——
export interface TaskStats {
  byStatus: Record<string, number>
  finished: number
  successRate: number | null
}
export interface AgentStat {
  agent: string
  steps: number
  avgMs: number | null
  p95Ms: number | null
  maxMs: number | null
  llmCalls: number
  tokensIn: number
  tokensOut: number
}
export interface TokenDay {
  date: string
  tokensIn: number
  tokensOut: number
}
export interface Overview {
  windowDays: number
  tasks: { today: TaskStats; window: TaskStats }
  riskEvents: Record<string, number>
  agents: AgentStat[]
  tokensByDay: TokenDay[]
  runtime: {
    llmGate: string
    taskSubmitted: number
    llmCalls: number
    tokensIn: number
    tokensOut: number
  }
}
export const getOverview = (days = 7) =>
  http.get<ApiResp<Overview>>('/admin/metrics/overview', { params: { days } }).then(r => r.data.data)

// —— H-05 可观测与成本护栏 ——
export interface GuardrailsFailureAlert {
  level: string
  message: string
  failed: number
  finished: number
  failureRate: number | null
  recent: { taskNo: string; pipelineCode: string; errorMsg: string; createdAt: string }[]
}
export interface GuardrailsCostBudget {
  windowHours: number
  tokensIn: number
  tokensOut: number
  totalTokens: number
  tokenBudget: number
  estimatedCost: number
  costBudget: number
  currency: string
  usedPercent: number
  level: string
  message: string
}
export interface GuardrailsQualityItem {
  reportId: number
  type: string
  title: string
  feedback: string | null
  riskLevel: string
  createdAt: string
  needsReview: boolean
}
export interface GuardrailsQualitySample {
  sampled: number
  rated: number
  useful: number
  unsure: number
  unhelpful: number
  unhelpfulRate: number | null
  level: string
  message: string
  items: GuardrailsQualityItem[]
}
export interface GuardrailsSlowRequest {
  method: string
  path: string
  status: number
  durationMs: number
  at: string
}
export interface Guardrails {
  generatedAt: string
  failureAlert: GuardrailsFailureAlert
  costBudget: GuardrailsCostBudget
  qualitySample: GuardrailsQualitySample
  slowRequests: GuardrailsSlowRequest[]
}
export const getGuardrails = (top = 10) =>
  http.get<ApiResp<Guardrails>>('/admin/metrics/guardrails', { params: { top } }).then(r => r.data.data)

// —— A1 任务监控 ——
export interface TaskRow {
  taskNo: string
  userId: number
  pipelineCode: string
  status: string
  createdAt: string
  finishedAt: string
  errorMsg: string | null
}
export interface TaskStep {
  stepSeq: number
  agentCode: string
  stepCode: string
  status: string
  attempt: number
  costMs: number | null
  llmCalls: number | null
  tokensIn: number | null
  tokensOut: number | null
  model: string | null
  errorMsg: string | null
  createdAt: string
}
export interface TaskDetail extends TaskRow {
  clientReqId: string | null
  startedAt: string
  steps: TaskStep[]
}
export const listTasks = (params: { pipelineCode?: string; status?: string; page?: number; size?: number }) =>
  http.get<ApiResp<PageResp<TaskRow>>>('/admin/tasks', { params }).then(r => r.data.data)
export const getTask = (taskNo: string) =>
  http.get<ApiResp<TaskDetail>>(`/admin/tasks/${taskNo}`).then(r => r.data.data)
export const retryTask = (taskNo: string) =>
  http
    .post<ApiResp<{ taskNo: string; status: string }>>(`/admin/tasks/${taskNo}/retry`, {})
    .then(r => r.data.data)

// —— A2 风险复核 ——
export interface RiskRow {
  id: number
  userId: number
  level: string
  triggerType: string
  ruleCode: string | null
  actionTaken: string | null
  taskId: number | null
  needsReview: number
  reviewed: number
  createdAt: string
}
export interface RevealResp {
  riskEventId: number
  userId: number
  evidence: unknown
}
export const riskQueue = (params: { reviewed?: number; level?: string; page?: number; size?: number }) =>
  http.get<ApiResp<PageResp<RiskRow>>>('/admin/risk/queue', { params }).then(r => r.data.data)
export const riskReveal = (id: number, password: string) =>
  http
    .post<ApiResp<RevealResp>>(`/admin/risk/${id}/reveal`, { password } satisfies Body<
      { password: string },
      'RevealBody'
    >)
    .then(r => r.data.data)
export const riskReview = (id: number, closeCrisis: boolean) =>
  http
    .post<ApiResp<{ riskEventId: number; reviewed: boolean; crisisClosed: boolean }>>(
      `/admin/risk/${id}/review`,
      { closeCrisis } satisfies Body<{ closeCrisis: boolean }, 'ReviewBody'>,
    )
    .then(r => r.data.data)

// —— A3 内容管理 ——
export interface KgNode {
  id: number
  type: string
  code: string
  name: string
  payloadJson: string
  status: number
  updatedAt: string
}
export interface SceneCard {
  id: number
  code: string
  title: string
  description: string
  difficulties: string | null
  personaJson: string | null
  goalDimensions: string | null
  maxTurns: number | null
  tags: string | null
  recommendedFor: string | null
  status: number
  updatedAt: string
}
export interface ExerciseItem {
  id: number
  code: string
  name: string
  applyEmotions: string | null
  stepsJson: string | null
  durationMin: number | null
  status: number
  updatedAt: string
}
export interface ContentWriteResp {
  code: string
  contentVersion: number
  effective: boolean
  revisionNo: number
}
export interface ContentDraft {
  contentType: string
  code: string
  payload: unknown
  baseRevisionId: number | null
  note: string | null
  updatedBy: number
  updatedAt: string
  valid: boolean
  errors: string[]
  warnings: string[]
}
export interface ContentPreview {
  contentType: string
  code: string
  valid: boolean
  errors: string[]
  warnings: string[]
  normalizedPayload: unknown
  summary: string
  baseRevisionId: number | null
  latestRevisionId: number | null
  conflict: boolean
}
export interface ContentPublish {
  contentType: string
  code: string
  revisionNo: number
  contentVersion: number
  effective: boolean
  warnings: string[]
}
export interface ContentRevision {
  id: number
  revisionNo: number
  action: string
  sourceRevisionId: number | null
  publishedBy: number
  publishedAt: string
  summary: string
}
export interface PreviewResp {
  template: string
  system: string
  output: string
  model: string
  tokensIn: number
  tokensOut: number
  costMs: number
}
export const listKg = (type?: string) =>
  http.get<ApiResp<KgNode[]>>('/admin/content/kg', { params: type ? { type } : {} }).then(r => r.data.data)
export const listScenes = () => http.get<ApiResp<SceneCard[]>>('/admin/content/scenes').then(r => r.data.data)
export const listExercises = () =>
  http.get<ApiResp<ExerciseItem[]>>('/admin/content/exercises').then(r => r.data.data)
export const upsertKgStatus = (code: string, status: number) =>
  http.put<ApiResp<ContentWriteResp>>(`/admin/content/kg/${code}`, { status }).then(r => r.data.data)
export const upsertSceneStatus = (code: string, status: number) =>
  http.put<ApiResp<ContentWriteResp>>(`/admin/content/scenes/${code}`, { status }).then(r => r.data.data)
export const upsertExerciseStatus = (code: string, status: number) =>
  http.put<ApiResp<ContentWriteResp>>(`/admin/content/exercises/${code}`, { status }).then(r => r.data.data)
export const refreshContent = () =>
  http.post<ApiResp<{ contentVersion: number }>>('/admin/content/refresh', {}).then(r => r.data.data)

// —— H-04 内容运营闭环 ——
export const getContentSnapshot = (type: string, code: string) =>
  http.get<ApiResp<unknown>>(`/admin/content/snapshot/${type}/${code}`).then(r => r.data.data)
export const listContentDrafts = (type?: string) =>
  http
    .get<ApiResp<ContentDraft[]>>('/admin/content/drafts', { params: type ? { type } : {} })
    .then(r => r.data.data)
export const getContentDraft = (type: string, code: string) =>
  http.get<ApiResp<ContentDraft>>(`/admin/content/drafts/${type}/${code}`).then(r => r.data.data)
export const saveContentDraft = (
  type: string,
  code: string,
  body: { payload: unknown; baseRevisionId?: number | null; note?: string },
) => http.put<ApiResp<ContentDraft>>(`/admin/content/drafts/${type}/${code}`, body).then(r => r.data.data)
export const deleteContentDraft = (type: string, code: string) =>
  http.delete<ApiResp<null>>(`/admin/content/drafts/${type}/${code}`).then(r => r.data.data)
export const previewContentDraft = (type: string, code: string) =>
  http
    .post<ApiResp<ContentPreview>>(`/admin/content/drafts/${type}/${code}/preview`, {})
    .then(r => r.data.data)
export const publishContentDraft = (type: string, code: string) =>
  http
    .post<ApiResp<ContentPublish>>(`/admin/content/drafts/${type}/${code}/publish`, {})
    .then(r => r.data.data)
export const listContentRevisions = (type: string, code: string) =>
  http.get<ApiResp<ContentRevision[]>>(`/admin/content/revisions/${type}/${code}`).then(r => r.data.data)
export const rollbackContentRevision = (type: string, code: string, revisionId: number) =>
  http
    .post<ApiResp<ContentPublish>>(`/admin/content/revisions/${type}/${code}/${revisionId}/rollback`, {})
    .then(r => r.data.data)
export type PreviewReq = Body<
  { template: string; user: string; vars?: Record<string, string> },
  'PreviewBody'
>
export const previewPrompt = (body: PreviewReq) =>
  http.post<ApiResp<PreviewResp>>('/admin/content/preview', body).then(r => r.data.data)

// —— A4 用户支持 ——
export interface UserTransition {
  from: string
  to: string
  reason: string
  at: string
}
export interface UserRow {
  id: number
  username: string
  nickname: string
  role: string
  status: number
  createdAt: string
  deletionRequestedAt: string
  crisisState?: string
  crisisStartedAt?: string
  crisisEndsAt?: string
  transitions?: UserTransition[]
}
export const listUsers = (params: { status?: number; q?: string; page?: number; size?: number }) =>
  http.get<ApiResp<PageResp<UserRow>>>('/admin/users', { params }).then(r => r.data.data)
export const crisisBoard = () =>
  http.get<ApiResp<UserRow[]>>('/admin/users/crisis-board').then(r => r.data.data)
export const freezeUser = (id: number) =>
  http
    .post<ApiResp<{ userId: number; status: number }>>(`/admin/users/${id}/freeze`, {})
    .then(r => r.data.data)
export const unfreezeUser = (id: number) =>
  http
    .post<ApiResp<{ userId: number; status: number }>>(`/admin/users/${id}/unfreeze`, {})
    .then(r => r.data.data)

// —— A5 审计与密钥 ——
export interface AuditRow {
  id: number
  userId: number
  action: string
  target: string
  ip: string
  createdAt: string
}
export interface VerifyResp {
  intact: boolean
  checked: number
  brokenAtId: number | null
}
export interface KeyStat {
  status: string
  keys: number
  maxVersion: number | null
  owners: number
}
export interface ExportRow {
  id: number
  userId: number
  kind: string
  status: string
  fileRef: string
  createdAt: string
  claimedAt: string
}
export const listAudit = (params: { userId?: number; action?: string; page?: number; size?: number }) =>
  http.get<ApiResp<PageResp<AuditRow>>>('/admin/ops/audit', { params }).then(r => r.data.data)
export const verifyAudit = () =>
  http.post<ApiResp<VerifyResp>>('/admin/ops/audit/verify', {}).then(r => r.data.data)
export const listKeys = () => http.get<ApiResp<KeyStat[]>>('/admin/ops/keys').then(r => r.data.data)
export const listExportRecords = (params: { page?: number; size?: number }) =>
  http.get<ApiResp<PageResp<ExportRow>>>('/admin/ops/export-records', { params }).then(r => r.data.data)

// —— M12 群体看板 ——
export interface GroupRow {
  id: number
  name: string
  status: number
  memberCount: number
  consentedCount: number
  suppressed: boolean
}
export interface GroupMember {
  userId: number
  username: string
  consented: boolean
}
export interface GroupDetail extends GroupRow {
  members: GroupMember[]
}
export interface GroupStats {
  groupId: number
  groupName: string
  windowDays: number
  memberCount: number
  consentedCount: number
  threshold: number
  suppressed: boolean
  reason?: string
  checkIns?: {
    contributors: number
    personDays: number
    avgRating: number | null
    avgEnergy: number | null
    emotions: { code: string; count: number }[]
  }
  valenceByDay?: { date: string; avgValence: number }[]
  hiddenDays?: number
  riskEvents?: Record<string, number>
}
export const listGroups = () => http.get<ApiResp<GroupRow[]>>('/admin/board/groups').then(r => r.data.data)
export const createGroup = (name: string) =>
  http
    .post<ApiResp<GroupDetail>>('/admin/board/groups', { name } satisfies Body<{ name: string }, 'GroupBody'>)
    .then(r => r.data.data)
export const getGroup = (id: number) =>
  http.get<ApiResp<GroupDetail>>(`/admin/board/groups/${id}`).then(r => r.data.data)
export const addGroupMembers = (id: number, userIds: number[]) =>
  http
    .post<ApiResp<GroupDetail>>(`/admin/board/groups/${id}/members`, { userIds } satisfies Body<
      { userIds: number[] },
      'MembersBody'
    >)
    .then(r => r.data.data)
export const removeGroupMember = (id: number, userId: number) =>
  http
    .delete<ApiResp<{ groupId: number; userId: number; removed: boolean }>>(
      `/admin/board/groups/${id}/members/${userId}`,
    )
    .then(r => r.data.data)
export const groupStats = (id: number, days = 7) =>
  http
    .get<ApiResp<GroupStats>>(`/admin/board/groups/${id}/stats`, { params: { days } })
    .then(r => r.data.data)
