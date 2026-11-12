import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const H = vi.hoisted(() => {
  const storage = new Map<string, string>()
  const uni = {
    getSystemInfoSync: () => ({ uniPlatform: 'h5' }),
    getStorageSync: (k: string) => storage.get(k) ?? '',
    setStorageSync: (k: string, v: string) => storage.set(k, v),
    removeStorageSync: (k: string) => storage.delete(k),
    request: vi.fn(),
    reLaunch: vi.fn(),
  }
  ;(globalThis as unknown as { uni: typeof uni }).uni = uni
  return {
    storage,
    fetchMe: vi.fn(),
    logout: vi.fn(),
    wechatLogin: vi.fn(),
    wechatBind: vi.fn(),
    wechatRegister: vi.fn(),
    wechatUnbind: vi.fn(),
    wxJsCode: vi.fn(),
  }
})

vi.mock('@/api/auth', () => ({
  fetchMe: H.fetchMe,
  logout: H.logout,
  wechatLogin: H.wechatLogin,
  wechatBind: H.wechatBind,
  wechatRegister: H.wechatRegister,
  wechatUnbind: H.wechatUnbind,
}))
vi.mock('@/api/wx', () => ({ wxJsCode: H.wxJsCode }))

import { getToken, setTokens } from '@/api/http'
import type { Me } from '@/api/auth'
import { useAuthStore } from './auth'

const ME = { userId: 1, nickname: '屿', role: 'USER', status: 1 } as unknown as Me

describe('小程序 stores/auth', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    H.storage.clear()
    H.fetchMe.mockReset()
    H.logout.mockReset()
    H.wechatLogin.mockReset()
    H.wechatBind.mockReset()
    H.wechatRegister.mockReset()
    H.wechatUnbind.mockReset()
    H.wxJsCode.mockReset()
  })

  it('冷启动无令牌：ready 直接置真，不请求 /auth/me', async () => {
    const store = useAuthStore()
    await expect(store.bootstrap()).resolves.toBeNull()
    expect(store.ready).toBe(true)
    expect(H.fetchMe).not.toHaveBeenCalled()
  })

  it('冷启动有令牌：拉取身份，失败也不拦首屏', async () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    H.fetchMe.mockResolvedValue(ME)
    const store = useAuthStore()
    await expect(store.bootstrap()).resolves.toEqual(ME)
    expect(store.me).toEqual(ME)
    expect(store.ready).toBe(true)
    expect(store.hasToken()).toBe(true)
  })

  it('登出：服务端吊销失败也要清本地会话', async () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    H.logout.mockRejectedValue(new Error('redis down'))
    const store = useAuthStore()
    store.me = ME
    await store.signOut()
    expect(getToken()).toBe('')
    expect(store.me).toBeNull()
    expect(store.ready).toBe(true)
  })

  it('微信已绑定：拿到令牌即入会话并清空绑定票', async () => {
    H.wxJsCode.mockResolvedValue('js-code')
    H.wechatLogin.mockResolvedValue({
      bound: true,
      token: { accessToken: 'a2', refreshToken: 'r2' },
    })
    H.fetchMe.mockResolvedValue(ME)
    const store = useAuthStore()
    store.bindTicket = 'stale'
    await store.signInWithWechat()
    expect(getToken()).toBe('a2')
    expect(store.bindTicket).toBe('')
    expect(store.me).toEqual(ME)
  })

  it('微信未绑定：只留一次性票，不写令牌', async () => {
    H.wxJsCode.mockResolvedValue('js-code')
    H.wechatLogin.mockResolvedValue({ bound: false, bindTicket: 'bt-1' })
    H.fetchMe.mockResolvedValue(null)
    const store = useAuthStore()
    await expect(store.signInWithWechat()).resolves.toMatchObject({ bound: false })
    expect(store.bindTicket).toBe('bt-1')
    expect(getToken()).toBe('')
  })
})
