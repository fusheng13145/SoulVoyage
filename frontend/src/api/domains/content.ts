import http, { type ApiResp } from '@/api/http'

export interface Scene {
  code: string
  title: string
  description: string
  npcName: string
  relation: string
  background?: string
  difficulties: string[]
  goalDimensions: string[]
  maxTurns: number
  tags?: string[]
  recommendedFor?: string[]
}

export interface ExerciseDef {
  id: string
  code?: string
  name: string
  applyEmotions: string[]
  steps: { step: string; desc: string }[]
  durationMin: number
}

export const contentApi = {
  scenes: () => http.get<ApiResp<Scene[]>>('/scenes').then(r => r.data.data),
  exercises: () => http.get<ApiResp<ExerciseDef[]>>('/exercises').then(r => r.data.data),
}
