import { beforeEach, describe, expect, it, vi } from 'vitest'

type UniRequestOptions = {
  url: string
  method: string
  data?: unknown
  header: Record<string, string>
  success: (res: { statusCode: number; data?: unknown }) => void
  fail: () => void
}

const H = vi.hoisted(() => {
  const storage = new Map<string, string>()
  const request = vi.fn()
  const reLaunch = vi.fn()
  const uni = {
    getSystemInfoSync: () => ({ uniPlatform: 'h5' }),
    getStorageSync: (k: string) => storage.get(k) ?? '',
    setStorageSync: (k: string, v: string) => storage.set(k, v),
    removeStorageSync: (k: string) => storage.delete(k),
    request,
    reLaunch,
  }
  ;(globalThis as unknown as { uni: typeof uni }).uni = uni
  return { storage, request, reLaunch }
})

import { clearTokens, get, getRefreshToken, getToken, post, readOr, setTokens } from './http'

const ok = (data: unknown) => ({
  statusCode: 200,
  data: { code: 0, msg: 'ok', data, traceId: 't' },
})
const err = (statusCode: number, code: number) => ({
  statusCode,
  data: { code, msg: '出错了', data: null, traceId: 't' },
})
const reply = (fn: (o: UniRequestOptions) => void) =>
  H.request.mockImplementation((o: UniRequestOptions) => fn(o))

describe('小程序 api/http', () => {
  beforeEach(() => {
    H.storage.clear()
    H.request.mockReset()
    H.reLaunch.mockReset()
  })

  it('令牌读写与清空只经 storage 一处', () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    expect(getToken()).toBe('a1')
    expect(getRefreshToken()).toBe('r1')
    clearTokens()
    expect(getToken()).toBe('')
    expect(getRefreshToken()).toBe('')
  })

  it('成功码 0 返回 data，并携带 Bearer 头', async () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    reply(o => {
      expect(o.header.Authorization).toBe('Bearer a1')
      o.success(ok({ n: 1 }))
    })
    await expect(get<{ n: number }>('/x')).resolves.toEqual({ n: 1 })
  })

  it('业务码非 0 抛带 code/status 的 ApiError', async () => {
    reply(o => o.success(err(400, 4001)))
    await expect(get('/x')).rejects.toMatchObject({ code: 4001, status: 400 })
  })

  it('网络失败 status=0 给可读中文话术', async () => {
    reply(o => o.fail())
    await expect(get('/x')).rejects.toThrow('网络不太顺')
  })

  it('并发 401 只刷新一次并重放（防 refresh 重用误吊销）', async () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    let hits = 0
    reply(o => {
      if (o.url.endsWith('/auth/refresh')) {
        return o.success(ok({ accessToken: 'a2', refreshToken: 'r2' }))
      }
      hits++
      o.success(hits <= 2 ? err(401, 1001) : ok({ ok: true }))
    })

    const [a, b] = await Promise.all([get('/x'), get('/x')])
    expect([a, b]).toEqual([{ ok: true }, { ok: true }])
    const refreshCalls = H.request.mock.calls.filter(c =>
      String((c[0] as UniRequestOptions).url).endsWith('/auth/refresh'),
    )
    expect(refreshCalls).toHaveLength(1)
    expect(getToken()).toBe('a2')
  })

  it('登录/注册自身 401 不触发全局回登录页', async () => {
    setTokens({ accessToken: 'a1', refreshToken: 'r1' })
    reply(o => o.success(err(401, 1003)))
    await expect(post('/auth/login', {})).rejects.toMatchObject({ code: 1003 })
    expect(H.reLaunch).not.toHaveBeenCalled()
  })

  it('非 auth 路径 401 且无 refresh：清令牌并 reLaunch 登录页', async () => {
    H.storage.set('sv_access', 'a1')
    reply(o => o.success(err(401, 1001)))
    await expect(get('/x')).rejects.toMatchObject({ code: 1001 })
    expect(getToken()).toBe('')
    expect(H.reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })

  it('readOr：主请求失败不打死首屏', async () => {
    reply(o => o.fail())
    await expect(readOr(get('/x'))).resolves.toBeNull()
  })
})
