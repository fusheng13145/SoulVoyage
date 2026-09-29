import { beforeEach, describe, expect, it, vi } from 'vitest'

type ThemeModule = typeof import('./theme')

/**
 * theme.ts 的模块顶层就执行了 useColorMode，配色偏好只在首次 import 时读取一次。
 * 因此每个用例都必须先重置模块缓存、布置好 matchMedia 与 localStorage，再动态 import，
 * 否则拿到的永远是第一个用例初始化出来的那份单例状态。
 */
async function loadTheme(systemDark: boolean): Promise<ThemeModule> {
  vi.resetModules()
  localStorage.clear()
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches: systemDark,
      media: '(prefers-color-scheme: dark)',
      addEventListener: () => {},
      removeEventListener: () => {},
    }),
  )
  return await import('./theme')
}

/**
 * data-theme 属性与 sv_theme 的落盘都由 useColorMode 内部的异步 watch 驱动，
 * 不是 setTheme 同步产生的副作用——断言前必须放行一轮微任务与定时器。
 */
const flush = () => new Promise<void>(resolve => setTimeout(resolve, 0))

describe('composables/theme 配色偏好', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('无历史偏好时默认为跟随系统', async () => {
    const t = await loadTheme(false)
    expect(t.themePref.value).toBe('system')
  })

  it('切到暗色后 isDark 为真，并把 data-theme 落到 documentElement 上', async () => {
    const t = await loadTheme(false)
    t.setTheme('dark')
    expect(t.themePref.value).toBe('dark')
    expect(t.isDark.value).toBe(true)
    await flush()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  it('切到亮色后 isDark 为假，即使系统当前是暗色', async () => {
    const t = await loadTheme(true)
    t.setTheme('light')
    expect(t.themePref.value).toBe('light')
    expect(t.isDark.value).toBe(false)
    await flush()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('跟随系统时，系统为暗色则 isDark 为真', async () => {
    const t = await loadTheme(true)
    t.setTheme('system')
    expect(t.themePref.value).toBe('system')
    expect(t.isDark.value).toBe(true)
  })

  it('跟随系统时，系统为亮色则 isDark 为假', async () => {
    const t = await loadTheme(false)
    t.setTheme('system')
    expect(t.themePref.value).toBe('system')
    expect(t.isDark.value).toBe(false)
  })

  it('偏好写入 sv_theme，供刷新后恢复', async () => {
    const t = await loadTheme(false)
    t.setTheme('dark')
    await flush()
    // vueuse 的 useStorage 默认按 JSON 序列化，故只断言包含关系，不锁死引号形式
    expect(localStorage.getItem('sv_theme')).toContain('dark')
  })

  it('从暗色切回亮色时 isDark 同步反转，不存在残留', async () => {
    const t = await loadTheme(false)
    t.setTheme('dark')
    expect(t.isDark.value).toBe(true)
    t.setTheme('light')
    expect(t.isDark.value).toBe(false)
  })
})
