import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http, { REFRESH_KEY, TOKEN_KEY } from '@/api/http'
import { useAuthStore } from './auth'

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  TOKEN_KEY: 'sv_access',
  REFRESH_KEY: 'sv_refresh',
}))

const get = vi.mocked(http.get)
const post = vi.mocked(http.post)

function resp<T>(data: T) {
  return { data: { code: 0, msg: 'ok', data, traceId: 't' } } as never
}

const TOKEN_PAYLOAD = {
  accessToken: 'at-1',
  refreshToken: 'rt-1',
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
    localStorage.clear()
    sessionStorage.clear()
  })

  it('login 写入双 token 并立即拉取 /auth/me', async () => {
    post.mockResolvedValueOnce(resp(TOKEN_PAYLOAD))
    get.mockResolvedValueOnce(resp({ userId: 7, nickname: '小屿', role: 'USER', status: 1 }))

    const store = useAuthStore()
    await store.login('u1', 'p1')

    expect(post).toHaveBeenCalledWith('/auth/login', { username: 'u1', password: 'p1' })
    expect(localStorage.getItem(TOKEN_KEY)).toBe('at-1')
    expect(localStorage.getItem(REFRESH_KEY)).toBe('rt-1')
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
    expect(localStorage.getItem(TOKEN_KEY)).toBe('at-1')
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

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
    expect(sessionStorage.getItem('sv_del_pending')).toBeNull()
    expect(store.me).toBeNull()
  })

  it('clear 清空 token、冷静期标记与内存中的 me', () => {
    localStorage.setItem(TOKEN_KEY, 'x')
    localStorage.setItem(REFRESH_KEY, 'y')
    sessionStorage.setItem('sv_del_pending', '1')

    const store = useAuthStore()
    store.clear()

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull()
    expect(sessionStorage.getItem('sv_del_pending')).toBeNull()
    expect(store.me).toBeNull()
  })
})
