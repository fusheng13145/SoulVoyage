import type { ExerciseDef } from '@/stores/content'

/** 练习目录条目（与 content store 同源） */
export type ExerciseView = ExerciseDef

/** 计划条目（/plans/active 与 /plans/{id} 共用） */
export interface PlanItemView {
  seq: number
  exerciseId: string
  guidance: string
  scheduledDate: string
  doneAt: string | null
  exerciseName: string
  durationMin: number
  feedback: string | null
}

/** 今日计划卡（/plans/active 的 plan） */
export interface ActivePlanView {
  planId: string
  title: string
  days: number
  daysLeft: number
  doneCount: number
  totalCount: number
  items: PlanItemView[]
}

/** 计划详情（/plans/{id}）：在卡片字段上增加起止日期与状态 */
export interface PlanDetailView extends ActivePlanView {
  startDate: string
  endDate: string
  status: string
}

/** 情绪轨迹点（/emotions/trajectory） */
export interface EmotionPointView {
  date: string
  emotion: string
  valence: string
  intensity: string
  sourceType: string
}

/** 周画像（crisis store 的 weeks） */
export interface WeekProfileView {
  statWeek: string
  avgValence: string | number
  riskLevel: string
  stressorTop: { name: string; count: number }[]
  distortionTop: { name: string; count: number }[]
}

/** H-02 周报摘要（/emotions/insights） */
export interface WeeklyDigestView {
  statWeek: string
  headline: string
  lines: string[]
}

/** H-02 波动提示：样本不足时数值字段为 null */
export interface VolatilityView {
  level: 'STEADY' | 'WAVY' | 'VOLATILE' | 'INSUFFICIENT'
  label: string
  hint: string
  sampleDays: number
  stdDev: string | number | null
  minValence: string | number | null
  maxValence: string | number | null
  maxDailyDelta: string | number | null
}

/** H-02 压力源解释 */
export interface StressorInsightView {
  name: string
  count: number
  what: string
  tip: string
}

/** H-02 误区解释 */
export interface DistortionInsightView {
  kgNodeId: string
  name: string
  count: number
  definition: string
  typicalSignature: string
  question: string
}

/** H-02 可执行下一步 */
export interface NextActionView {
  code: string
  title: string
  detail: string
  route: string
}

/** H-02 洞察总响应 */
export interface EmotionInsightView {
  generatedFor: string
  weeks: number
  crisisMode: boolean
  weekly: WeeklyDigestView[]
  volatility: VolatilityView
  stressors: StressorInsightView[]
  distortions: DistortionInsightView[]
  actions: NextActionView[]
}
