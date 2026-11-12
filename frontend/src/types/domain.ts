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
