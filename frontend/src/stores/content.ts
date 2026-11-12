import { defineStore } from 'pinia'
import { ref } from 'vue'
import { contentApi, type ExerciseDef, type Scene } from '@/api/domains/content'

export type { ExerciseDef, Scene } from '@/api/domains/content'

/** 低频内容目录全局一份：场景卡 / 练习库（消灭各页重复拉取） */
export const useContentStore = defineStore('content', () => {
  const scenes = ref<Scene[] | null>(null)
  const exercises = ref<ExerciseDef[] | null>(null)

  async function ensureScenes(force = false) {
    if (scenes.value && !force) return scenes.value
    scenes.value = await contentApi.scenes()
    return scenes.value
  }

  async function ensureExercises(force = false) {
    if (exercises.value && !force) return exercises.value
    exercises.value = await contentApi.exercises()
    return exercises.value
  }

  return { scenes, exercises, ensureScenes, ensureExercises }
})
