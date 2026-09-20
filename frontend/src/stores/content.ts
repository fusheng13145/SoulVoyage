import { defineStore } from 'pinia'
import { ref } from 'vue'
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

/** 低频内容目录全局一份：场景卡 / 练习库（消灭各页重复拉取） */
export const useContentStore = defineStore('content', () => {
  const scenes = ref<Scene[] | null>(null)
  const exercises = ref<ExerciseDef[] | null>(null)

  async function ensureScenes(force = false) {
    if (scenes.value && !force) return scenes.value
    const { data } = await http.get<ApiResp<Scene[]>>('/scenes')
    scenes.value = data.data
    return scenes.value
  }

  async function ensureExercises(force = false) {
    if (exercises.value && !force) return exercises.value
    const { data } = await http.get<ApiResp<ExerciseDef[]>>('/exercises')
    exercises.value = data.data
    return exercises.value
  }

  return { scenes, exercises, ensureScenes, ensureExercises }
})
