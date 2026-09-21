import { computed, ref } from 'vue'
import { THEME_KEY } from '@/config'

type ThemeMode = 'auto' | 'light' | 'dark'

const mode = ref<ThemeMode>('auto')
const systemDark = ref(false)

/** 端侧深色：小程序拿不到 <html> 的 data-theme，所以由页面根节点挂 .sv-dark 承接变量，
 *  导航栏/标签栏这类原生区域再单独走 API 设色——它们不吃 CSS 变量。 */
export function useTheme() {
  const isDark = computed(() => mode.value === 'dark' || (mode.value === 'auto' && systemDark.value))
  return {
    mode,
    isDark,
    /** 页面根节点：`<view class="sv-page" :class="themeClass">` */
    themeClass: computed(() => (isDark.value ? 'sv-dark' : '')),
    setMode,
  }
}

function setMode(next: ThemeMode) {
  mode.value = next
  uni.setStorageSync(THEME_KEY, next)
  applyTheme()
}

/** tab 页在 onShow 里调一次：原生区域不吃 CSS 变量，只能在页面可见时按当前态刷 */
export function applyTheme() {
  const dark = mode.value === 'dark' || (mode.value === 'auto' && systemDark.value)
  uni.setNavigationBarColor({
    frontColor: dark ? '#ffffff' : '#000000',
    backgroundColor: dark ? '#000000' : '#f2f2f7',
    fail: () => {},
  })
  uni.setTabBarStyle({
    color: dark ? '#98989f' : '#8e8e93',
    selectedColor: dark ? '#7a88ff' : '#5b6cff',
    backgroundColor: dark ? '#1c1c1e' : '#ffffff',
    fail: () => {}, // 非 tab 页（登录、写作页）调用会失败：那里本来就没有标签栏
  })
}

export function initTheme() {
  const saved = (uni.getStorageSync(THEME_KEY) as ThemeMode) || 'auto'
  mode.value = saved
  try {
    systemDark.value = uni.getSystemInfoSync().theme === 'dark'
  } catch {
    /* 拿不到系统主题就当浅色：宁可错显一次，也不要启动即报错 */
  }
  applyTheme()
}
