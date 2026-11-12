import { computed } from 'vue'
import { useColorMode, usePreferredDark } from '@vueuse/core'

export type ThemePref = 'system' | 'light' | 'dark'

/** 跟随系统 + 手动切换（偏好存 localStorage，M7 并入 user_preferences） */
const mode = useColorMode<ThemePref>({
  storageKey: 'sv_theme',
  initialValue: 'system',
  attribute: 'data-theme',
  modes: { system: 'light', light: 'light', dark: 'dark' },
  emitAuto: true,
})

const preferredDark = usePreferredDark()

export const themePref = computed(() => mode.value as ThemePref)
export const isDark = computed(() => {
  if (mode.value === 'dark') return true
  if (mode.value === 'light') return false
  return preferredDark.value
})

export function setTheme(pref: ThemePref) {
  mode.value = pref
}
