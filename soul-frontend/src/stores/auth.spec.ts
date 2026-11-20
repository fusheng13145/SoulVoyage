import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const S = vi.hoisted(() => ({ token: null as string | null, ensure: vi.fn() }))

import http from '@/api/http'
import { useAuthStore } from './auth'

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  // 用模块级变量模拟"内存里的 access"，确保断言不落 localStorage
  setAccessToken: (t: string | null) => {
    S.token = t
  },
  clearAccessToken: () => {
    S.token = null
  },
  ensureSession: S.ensure,
}))

const get = vi.mocked(http.get)
const post = vi.mocked(http.post)

function resp<T>(data: T) {
  return { data: { code: 0, msg: 'ok', data, traceId: 't' } } as never
}

const TOKEN_PAYLOAD = {
  accessToken: 'at-1',
  // 浏览器端后端已把 refresh 收进 HttpOnly Cookie，body 里恒为 null
  refreshToken: null,
  userId: 7,
  nickname: '小屿',
  role: 'USER',
  deletionPending: false,
}

describe('stores/auth 会话唯一事实源', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    get.mockReset()
    post.mockReset()
    S.token = null
    S.ensure.mockReset()
    sessionStorage.clear()
  })

  it('login 把 access 放进内存并立即拉取 /auth/me', async () => {
    post.mockResolvedValueOnce(resp(TOKEN_PAYLOAD))
    get.mockResolvedValueOnce(resp({ userId: 7, nickname: '小屿', role: 'USER', status: 1 }))

    const store = useAuthStore()
    await store.login('u1', 'p1')

    expect(post).toHaveBeenCalledWith('/auth/login', { username: 'u1', password: 'p1' })
    expect(S.token).toBe('at-1')
    expect(store.authed).toBe(true)
    expect(store.me?.userId).toBe(7)
    expect(store.loaded).toBe(true)
  })

  it('register 走同一套落 token 与拉取流程', async () => {
    post.mockResolvedValueOnce(resp(TOKEN_PAYLOAD))
    get.mockResolvedValueOnce(resp({ userId: 7, nickname: '小屿' }))

    const store = useAuthStore()
    await store.register('u1', 'p1', '小屿')

    expect(post).toHaveBeenCalledWith('/auth/register', {
      username: 'u1',
      password: 'p1',
      nickname: '小屿',
    })
    expect(S.token).toBe('at-1')
  })

  it('deletionPending 为真时打上冷静期标记，为假时清除', async () => {
    post.mockResolvedValueOnce(resp({ ...TOKEN_PAYLOAD, deletionPending: true }))
    get.mockResolvedValueOnce(resp({ userId: 7 }))

    const store = useAuthStore()
    await store.login('u1', 'p1')
    expect(sessionStorage.getItem('sv_del_pending')).toBe('1')

    store.flagDeletion(false)
    expect(sessionStorage.getItem('sv_del_pending')).toBeNull()
  })

  it('restore：Cookie 续期成功则标记已登录并拉档案', async () => {
    S.ensure.mockResolvedValueOnce(true)
    get.mockResolvedValueOnce(resp({ userId: 7 }))

    const store = useAuthStore()
    await store.restore()

    expect(S.ensure).toHaveBeenCalledTimes(1)
    expect(store.authed).toBe(true)
    expect(get).toHaveBeenCalledTimes(1)
  })

  it('restore：Cookie 也没有时保持未登录且不请求 /auth/me', async () => {
    S.ensure.mockResolvedValueOnce(false)

    const store = useAuthStore()
    await store.restore()

    expect(store.authed).toBe(false)
    expect(get).not.toHaveBeenCalled()
  })

  it('restore：已登录时直接返回，不再重复探测', async () => {
    post.mockResolvedValueOnce(resp(TOKEN_PAYLOAD))
    get.mockResolvedValueOnce(resp({ userId: 7 }))
    const store = useAuthStore()
    await store.login('u1', 'p1')
    S.ensure.mockClear()

    await store.restore()
    expect(S.ensure).not.toHaveBeenCalled()
  })

  it('fetchMe 已有缓存且非 force 时不再发请求', async () => {
    get.mockResolvedValueOnce(resp({ userId: 7 }))

    const store = useAuthStore()
    await store.fetchMe()
    await store.fetchMe()

    expect(get).toHaveBeenCalledTimes(1)
  })

  it('fetchMe(force) 强制刷新覆盖缓存', async () => {
    get.mockResolvedValueOnce(resp({ userId: 7 }))
    get.mockResolvedValueOnce(resp({ userId: 8 }))

    const store = useAuthStore()
    await store.fetchMe()
    const fresh = await store.fetchMe(true)

    expect(get).toHaveBeenCalledTimes(2)
    expect(fresh.userId).toBe(8)
  })

  it('logout 即便服务端吊销失败也要完成本地登出', async () => {
    post.mockResolvedValueOnce(resp(TOKEN_PAYLOAD))
    get.mockResolvedValueOnce(resp({ userId: 7 }))
    const store = useAuthStore()
    await store.login('u1', 'p1')

    post.mockRejectedValueOnce(new Error('network down'))
    await store.logout()

    expect(S.token).toBeNull()
    expect(store.authed).toBe(false)
    expect(sessionStorage.getItem('sv_del_pending')).toBeNull()
    expect(store.me).toBeNull()
  })

  it('clear 清空 access、冷静期标记与内存中的 me', () => {
    sessionStorage.setItem('sv_del_pending', '1')
    S.token = 'x'

    const store = useAuthStore()
    store.clear()

    expect(S.token).toBeNull()
    expect(store.authed).toBe(false)
    expect(sessionStorage.getItem('sv_del_pending')).toBeNull()
    expect(store.me).toBeNull()
  })
})
