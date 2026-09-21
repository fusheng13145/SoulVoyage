import { del, get, post, put } from '@/api/http'
import { postStream, type SseEvent } from '@/api/sseText'
import type { components } from '@/api/schema'

/** 端侧领域接口封装（M11 页面集共用）。
 *  能钉住形状的请求体一律用 OpenAPI 生成物钉（`Body<B,K>`），字段名写错在 vue-tsc 阶段就红；
 *  返回 `Map<String,Object>` 的接口（读物/计划/通知/日记/资源/偏好）在生成物里是空对象，
 *  这里按后端 Service 的实际键面声明成本地接口——两边一旦漂移，真机冒烟会第一时间抓到。 */
type Body<B extends object, K extends keyof components['schemas']> = B & [B] extends [
  components['schemas'][K],
]
  ? unknown
  : never

/** 一次打卡的回执：note 是密文落库后回读给本人的明文，别处不外传 */
export interface CheckInView {
  date: string
  emotionCode: string
  rating: number | null
  energy: number | null
  note: string | null
  madeUp: boolean
  valence: number | null
}

export interface StreakView {
  current: number
  longest: number
  totalDays: number
  makeupAvailable: boolean
}

export interface ReadingCard {
  kgNodeId: string
  title: string
  summary: string
  microAction: string
  aboutTags: string[]
  readingSec: number
  date: string
  favorited: boolean
  firstToday: boolean
}

export interface FavoriteItem {
  type: string
  refCode: string
  title: string
  summary: string
}

export interface PlanItem {
  seq: number
  exerciseId: number
  exerciseName: string
  guidance: string
  scheduledDate: string
  doneAt: string | null
  feedback: string | null
  durationMin: number
}

export interface ActivePlan {
  planId: string
  title: string
  days: number
  status: string
  startDate: string
  endDate: string
  daysLeft: number
  doneCount: number
  totalCount: number
  items: PlanItem[]
}

export interface Notice {
  id: number
  kind: string
  title: string
  body: string
  link: string
  readAt: string | null
  createdAt: string
}

/** 后端对"没有值"下发的是 0 而不是 null（moodSelfRating/taskId），类型跟着事实走 */
export interface DiaryRow {
  id: number
  recordDate: string
  moodSelfRating: number
  taskId: number
  source: string
  preview: string
}

export interface DiaryReport {
  id: number
  type: string
  title: string
  riskLevel: string
  stale: number
}

export interface DiaryEmotion {
  sourceType: string
  emotion: string
  valence: string
  intensity: string
  eventTags: string[]
}

export interface DiaryDetail {
  id: number
  recordDate: string
  moodSelfRating: number
  content: string
  source: string
  voiceDurationMs: number
  taskId: number
  reports: DiaryReport[]
  emotions: DiaryEmotion[]
}

export interface CrisisResource {
  name: string
  value: string
  type: string
  note: string
}

export interface Preferences {
  theme: string
  checkinReminderOn: boolean
  reminderTime: string
  planReminderOn: boolean
  letterOn: boolean
  hapticOn: boolean
  companionAnalysisOn: boolean
  counselorBoardOn: boolean
}

export type PrefKey = keyof Preferences

export interface SessionView {
  sessionId: number
  chatDate: string
  segmentNo: number
  status: string
  turns: number
  turnList: TurnView[]
}

export interface TurnView {
  turnId: number
  turnNo: number
  userText: string
  aiText: string
  moodTag: string | null
  crisis: boolean
  noAnalyze: boolean
  createdAt?: string
}

/** turn_done 的载荷：aiText 是该轮权威全文，客户端以它收尾而不是拿增量拼 */
export interface TurnDone {
  turnId: number
  turnNo: number
  moodTag: string | null
  aiText: string
  crisis: boolean
  guidanceShown: boolean
  remainingToday: number
}

export interface CrisisEvent {
  level: string
  hotline: string
  message: string
}

/** 把一轮的 SSE 事件拆成（危机事件, turn_done）；缺 turn_done 由 postStream 先抛错 */
export function readTurn(events: SseEvent[]) {
  const done = events.find(e => e.event === 'turn_done')?.data as unknown as TurnDone
  const crisis = events.find(e => e.event === 'crisis')?.data as unknown as CrisisEvent | undefined
  return { done, crisis }
}

/* ── 觉察：打卡与连续记录 ── */
export interface CheckInInput {
  emotion: string
  rating?: number
  energy?: number
  note?: string
}

export const checkIn = (input: CheckInInput) =>
  post<CheckInView>('/mood-check-ins', input satisfies Body<typeof input, 'CheckInReq'>)

/** 同一张盘也可以记过去的日子：走普通打卡（不算补签，不动每月一次的名额） */
export const checkInOn = (input: CheckInInput, date: string) =>
  post<CheckInView>(`/mood-check-ins?date=${date}`, input satisfies Body<typeof input, 'CheckInReq'>)

/** 补签：往日、14 天内、每自然月一次，规则由后端裁决，端侧只转达话术 */
export const makeupCheckIn = (input: CheckInInput, date: string) =>
  post<CheckInView>(`/mood-check-ins/makeup?date=${date}`, input satisfies Body<typeof input, 'CheckInReq'>)

export const monthCheckIns = (month: string) =>
  get<{ items: CheckInView[]; today: CheckInView | null }>(`/mood-check-ins?month=${month}`)

export const streak = () => get<StreakView>('/mood-check-ins/streak')

/** 情绪轨迹点：emotion 是中文标签（EmotionAgent 产出），valence/intensity 后端按字符串口径下发 */
export interface TrajPoint {
  date: string
  sourceType: string
  emotion: string
  valence: string
  intensity: string
}

export const trajectory = (from: string, to: string) =>
  get<{ points: TrajPoint[] }>(`/emotions/trajectory?from=${from}&to=${to}`)

/* ── 今日一读与收藏 ── */
export const todayReading = () => get<ReadingCard>('/readings/today')

export const toggleFavorite = (refCode: string) =>
  post<{ type: string; refCode: string; favorited: boolean }>('/readings/favorites', {
    type: 'PSY_TOPIC',
    refCode,
  } satisfies Body<{ type: string; refCode: string }, 'FavoriteReq'>)

export const favorites = () => get<FavoriteItem[]>('/readings/favorites')

/* ── 今日计划（G4 成长计划物化后的当日视图） ── */
export const activePlan = () => get<{ plan: ActivePlan | null }>('/plans/active')

/* ── 通知 ── */
export const notifications = (page = 0, size = 20) =>
  get<{ items: Notice[]; total: number; unreadCount: number }>(`/notifications?page=${page}&size=${size}`)

export const readNotice = (id: number) => post<null>(`/notifications/${id}/read`)

export const readAllNotices = () => post<{ read: number }>('/notifications/read-all')

/* ── 日记：草稿 / 建档（走 DIARY_PIPELINE）/ 列表 / 详情 ── */
export const diaryList = (
  q: { from?: string; to?: string; q?: string; page?: number; size?: number } = {},
) => {
  const qs = Object.entries({ page: 0, size: 20, ...q })
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join('&')
  return get<{ items: DiaryRow[]; page: number; size: number; total: number }>(`/diaries?${qs}`)
}

export const diaryDetail = (id: number) => get<DiaryDetail>(`/diaries/${id}`)

export const diaryDraft = () => get<{ content: string }>('/diaries/draft')

export const saveDraft = (content: string) =>
  put<null>('/diaries/draft', { content } satisfies Body<{ content: string }, 'DraftReq'>)

export const dropDraft = () => del<null>('/diaries/draft')

/** 编辑走 PUT：正文密文重新落库，重分析由后端把 stale 打上，端侧不自己判新旧 */
export const editDiary = (id: number, content: string, moodSelfRating?: number) =>
  put<DiaryDetail>(
    `/diaries/${id}`,
    (moodSelfRating == null ? { content } : { content, moodSelfRating }) satisfies Body<
      { content: string; moodSelfRating?: number },
      'DiaryEditReq'
    >,
  )

export const deleteDiary = (id: number) => del<null>(`/diaries/${id}`)

/** 重分析只回任务号：与首次建档同一套异步口径 */
export const reanalyzeDiary = (id: number) => post<{ taskNo: string }>(`/diaries/${id}/reanalyze`)

/** 建档只回任务号：日记内容由管道异步产出，页面轮询 taskOf 到 DONE 再跳详情。
 *  payload 在生成物里就是无形状 JsonNode（后端按管道自解析），所以这一处不钉 Body，只钉外层。 */
export const submitDiary = (input: {
  diaryText: string
  recordDate: string
  moodSelfRating?: number
  diarySource?: string
  voiceDurationMs?: number
  clientReqId: string
}) =>
  post<{ taskNo: string; status: string }>('/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    payload: {
      diaryText: input.diaryText,
      recordDate: input.recordDate,
      ...(input.moodSelfRating != null ? { moodSelfRating: input.moodSelfRating } : {}),
      ...(input.diarySource ? { diarySource: input.diarySource } : {}),
      ...(input.voiceDurationMs ? { voiceDurationMs: input.voiceDurationMs } : {}),
    },
    clientReqId: input.clientReqId,
  })

export const taskOf = (taskNo: string) =>
  get<{
    taskNo: string
    pipelineCode: string
    status: string
    payload: Record<string, unknown> | null
    errorMsg: string | null
  }>(`/tasks/${taskNo}`)

/* ── 危机资源：公开接口，未登录也要能打通 ── */
export const crisisResources = () => get<{ boundary: string; resources: CrisisResource[] }>('/risk/resources')

/** 资源页的最后一道保险：接口挂了也不能让人空手离开 */
export const CRISIS_FALLBACK: { boundary: string; resources: CrisisResource[] } = {
  boundary: '这里不是急诊，但你值得立刻被接住。',
  resources: [
    { name: '全国心理援助热线', value: '12356', type: 'PHONE', note: '24 小时' },
    { name: '北京心理危机研究与干预中心', value: '010-82951332', type: 'PHONE', note: '24 小时' },
    { name: '奇祥热线', value: '400-161-9995', type: 'PHONE', note: '24 小时' },
  ],
}

/* ── 偏好：白名单键，未列出的键后端静默忽略 ── */
export const prefs = () => get<Preferences>('/preferences')

export const putPrefs = (patch: Partial<Preferences>) => put<Preferences>('/preferences', patch)

/* ── 陪伴：树洞漫聊 ── */
export const companionActive = () => get<SessionView | null>('/companion/active')

/** 开段/续段是 POST（同段直接续，静默超窗或跨日自动开新段），GET 那个口只用于读活跃段 */
export const companionOpen = () => post<SessionView>('/companion/sessions')

export const companionHistory = (page = 0, size = 20) =>
  get<{ page: number; size: number; total: number; sessions: SessionView[] }>(
    `/companion/sessions?page=${page}&size=${size}`,
  )

export const companionTranscript = (id: number) => get<SessionView>(`/companion/sessions/${id}`)

/** 逐轮流式：小程序没有 EventSource，这里收完整段再解析（见 sseText 的取舍说明） */
export const companionTurn = async (id: number, userText: string) =>
  readTurn(
    await postStream(`/companion/sessions/${id}/turns`, { userText } satisfies Body<
      { userText: string },
      'TurnReq'
    >),
  )

export const companionNoAnalyze = (turnId: number) =>
  post<{ turnId: number; noAnalyze: boolean }>(`/companion/turns/${turnId}/no-analyze`)

export const companionEnd = (id: number) =>
  post<{ sessionId: number; taskNo: string }>(`/companion/sessions/${id}/end`)

/* ── 账号：注销申请与撤回（要口令，端侧不缓存） ── */
export const requestDelete = (password: string) =>
  post<null>('/users/me/delete', { password } satisfies Body<{ password: string }, 'DeleteReq'>)

export const cancelDelete = () => post<null>('/users/me/delete/cancel')

export const changePassword = (oldPassword: string, newPassword: string) =>
  put<null>('/users/me/password', { oldPassword, newPassword } satisfies Body<
    { oldPassword: string; newPassword: string },
    'PasswordReq'
  >)
