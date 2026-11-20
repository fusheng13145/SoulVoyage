import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

type ReqCfg = { headers: Record<string, string> }

const H = vi.hoisted(() => ({
  handlers: {} as {
    req?: (cfg: ReqCfg) => unknown
    resOk?: (res: unknown) => unknown
    resErr?: (err: unknown) => unknown
  },
  routerPush: vi.fn(),
  refreshPost: vi.fn(),
}))

vi.mock('axios', () => {
  // axios 实例本身可调用（错误分支里用 http(original) 重放），故 mock 也必须是函数
  const instance = Object.assign((cfg: unknown) => Promise.resolve(cfg), {
    interceptors: {
      request: {
        use: (f: (cfg: ReqCfg) => unknown) => {
          H.handlers.req = f
        },
      },
      response: {
        use: (ok: (res: unknown) => unknown, err: (e: unknown) => unknown) => {
          H.handlers.resOk = ok
          H.handlers.resErr = err
        },
      },
    },
  })
  return { default: { create: () => instance, post: H.refreshPost } }
})

vi.mock('../router', () => ({ default: { push: H.routerPush } }))

import {
  CONFLICT_CODE,
  authFetch,
  clearAccessToken,
  ensureSession,
  getAccessToken,
  isConflict,
  setAccessToken,
} from './http'

/** 让 refreshOnce 的 finally 里那个 setTimeout(...,0) 有机会跑完，重置模块级 refreshing */
const flushMacrotask = () => new Promise(r => setTimeout(r, 0))

describe('api/http 请求与响应拦截器（memory access + HttpOnly refresh）', () => {
  beforeEach(() => {
    clearAccessToken()
    H.routerPush.mockClear()
    H.refreshPost.mockReset()
    expect(H.handlers.req, '拦截器应已注册').toBeTypeOf('function')
  })

  it('请求拦截器：内存里有 access 时注入 Bearer 头', () => {
    setAccessToken('tk-1')
    const cfg: ReqCfg = { headers: {} }
    H.handlers.req!(cfg)
    expect(cfg.headers.Authorization).toBe('Bearer tk-1')
  })

  it('请求拦截器：无 token 时不注入 Authorization', () => {
    const cfg: ReqCfg = { headers: {} }
    H.handlers.req!(cfg)
    expect(cfg.headers.Authorization).toBeUndefined()
  })

  it('408 前提：令牌绝不写 localStorage（XSS 拿不到长效凭证）', () => {
    setAccessToken('tk-1')
    expect(localStorage.getItem('sv_access')).toBeNull()
    expect(localStorage.getItem('sv_refresh')).toBeNull()
  })

  it('成功响应：code === 0 原样放行', () => {
    const res = { data: { code: 0, msg: 'ok' } }
    expect(H.handlers.resOk!(res)).toBe(res)
  })

  it('成功响应：业务 code 非 0 时转成带 code 的 Error', async () => {
    await expect(H.handlers.resOk!({ data: { code: 2001, msg: '无权限' } })).rejects.toMatchObject({
      message: '无权限',
      code: 2001,
    })
  })

  it('401 且非 auth 路径：Cookie 刷新成功后带新 access 重放原请求', async () => {
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'at-2', refreshToken: null } } })
    const original: Record<string, unknown> = { url: '/diaries' }

    const result = await H.handlers.resErr!({ response: { status: 401 }, config: original })

    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    // refresh 不带 body，靠浏览器自动附 Cookie
    expect(H.refreshPost).toHaveBeenCalledWith('/api/v1/auth/refresh', null, { withCredentials: true })
    expect(getAccessToken()).toBe('at-2')
    expect(original._retried).toBe(true)
    expect(result).toEqual(original)
    await flushMacrotask()
  })

  it('401 且刷新失败：清空内存 access 并跳登录页', async () => {
    setAccessToken('at-1')
    H.refreshPost.mockRejectedValue(new Error('refresh rejected'))

    await expect(
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/diaries' } }),
    ).rejects.toBeTruthy()

    expect(getAccessToken()).toBeNull()
    expect(H.routerPush).toHaveBeenCalledWith('/login')
    await flushMacrotask()
  })

  it('auth 路径的 401 不触发全局登出（登录失败不该把用户踢出去）', async () => {
    await expect(
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/auth/login' } }),
    ).rejects.toBeTruthy()

    expect(H.routerPush).not.toHaveBeenCalled()
    await flushMacrotask()
  })

  it('已重放过的请求再 401 时不进入刷新循环', async () => {
    const original: Record<string, unknown> = { url: '/diaries', _retried: true }

    await expect(H.handlers.resErr!({ response: { status: 401 }, config: original })).rejects.toBeTruthy()

    expect(H.refreshPost).not.toHaveBeenCalled()
    await flushMacrotask()
  })

  it('非 401 的业务错误响应转成带 code 的 Error', async () => {
    await expect(
      H.handlers.resErr!({
        response: { status: 400, data: { code: 4001, msg: '参数不对' } },
        config: { url: '/x' },
      }),
    ).rejects.toMatchObject({ message: '参数不对', code: 4001 })
  })

  it('无响应体的网络错误原样抛出', async () => {
    const err = new Error('Network Error')
    await expect(H.handlers.resErr!(err)).rejects.toBe(err)
  })

  it('并发 401 共享同一次刷新（旧 refresh 用两次会被后端判失窃）', async () => {
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'at-9' } } })

    await Promise.all([
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/a' } }),
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/b' } }),
    ])

    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    await flushMacrotask()
  })

  it('401 响应缺少 config 时仍能刷新并重放（不因取不到原请求而崩）', async () => {
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'at-3' } } })

    const result = await H.handlers.resErr!({ response: { status: 401 } })

    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    expect(result).toBeUndefined() // mock 实例把 http(undefined) 原样 resolve
    await flushMacrotask()
  })

  it('成功响应业务 code 非 0 且无 msg 时，回落默认话术', async () => {
    await expect(H.handlers.resOk!({ data: { code: 2001 } })).rejects.toMatchObject({
      message: '请求失败',
      code: 2001,
    })
  })

  it('错误响应体无 msg 时同样回落默认话术', async () => {
    await expect(
      H.handlers.resErr!({ response: { status: 400, data: { code: 4001 } }, config: { url: '/x' } }),
    ).rejects.toMatchObject({ message: '请求失败', code: 4001 })
  })

  it('成功响应无响应体时原样放行（不做 code 判定）', () => {
    const res = { data: undefined }
    expect(H.handlers.resOk!(res)).toBe(res)
  })

  it('错误响应体 code 为 0 时原样抛出原错误（不当业务错误改写）', async () => {
    const err = { response: { status: 500, data: { code: 0, msg: 'ok' } }, config: { url: '/x' } }
    await expect(H.handlers.resErr!(err)).rejects.toBe(err)
  })

  it('错误响应体 code 非数字时原样抛出原错误', async () => {
    const err = {
      response: { status: 500, data: { code: 'E500', msg: 'boom' } },
      config: { url: '/x' },
    }
    await expect(H.handlers.resErr!(err)).rejects.toBe(err)
  })

  it('B-09：409 + 2003 被识别为乐观锁冲突，其他错误不误判', async () => {
    const conflict = { response: { status: 409, data: { code: 2003 } }, config: { url: '/preferences' } }
    await expect(H.handlers.resErr!(conflict)).rejects.toMatchObject({ code: CONFLICT_CODE })
    expect(isConflict({ code: CONFLICT_CODE })).toBe(true)
    expect(isConflict({ code: 4001 })).toBe(false)
    expect(isConflict(new Error('x'))).toBe(false)
    expect(isConflict(null)).toBe(false)
  })

  it('ensureSession：无 access 时静默续期一次并回填内存', async () => {
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'boot' } } })

    await expect(ensureSession()).resolves.toBe(true)
    expect(getAccessToken()).toBe('boot')
    H.refreshPost.mockClear()

    // 已有 access 不再打接口
    await expect(ensureSession()).resolves.toBe(true)
    expect(H.refreshPost).not.toHaveBeenCalled()
    await flushMacrotask()
  })

  it('ensureSession：Cookie 也已失效时只探测一次，之后直接返回 false', async () => {
    H.refreshPost.mockRejectedValue(new Error('401'))

    await expect(ensureSession()).resolves.toBe(false)
    await expect(ensureSession()).resolves.toBe(false)
    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    await flushMacrotask()
  })
})

describe('api/http authFetch（SSE 带鉴权）', () => {
  beforeEach(() => {
    clearAccessToken()
    H.refreshPost.mockReset()
  })

  it('B-07 authFetch：401 后单飞刷新并带新令牌重试', async () => {
    setAccessToken('old')
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'new' } } })
    const auths: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_u: string, init: { headers: HeadersInit }) => {
        auths.push(new Headers(init.headers).get('Authorization') || '')
        return Promise.resolve({ status: auths.length === 1 ? 401 : 200 } as Response)
      }),
    )

    const res = await authFetch('/api/v1/tasks/T-1/stream')
    expect(res.status).toBe(200)
    expect(auths).toEqual(['Bearer old', 'Bearer new'])
    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBe('new')
    await flushMacrotask()
  })

  it('B-07 并发 401 只刷新一次（跨调用单飞）', async () => {
    setAccessToken('old')
    H.refreshPost.mockResolvedValue({ data: { data: { accessToken: 'new' } } })
    let n = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ status: n++ < 2 ? 401 : 200 } as Response)),
    )

    const [a, b] = await Promise.all([authFetch('/a'), authFetch('/b')])
    expect([a.status, b.status]).toEqual([200, 200])
    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    await flushMacrotask()
  })

  it('B-07 authFetch：刷新失败时清空内存会话并保留 401', async () => {
    setAccessToken('only-access')
    H.refreshPost.mockRejectedValue(new Error('no cookie'))
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ status: 401 } as Response)),
    )

    const res = await authFetch('/x')
    expect(res.status).toBe(401)
    expect(getAccessToken()).toBeNull()
    expect(localStorage.getItem('sv_access')).toBeNull()
    await flushMacrotask()
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})
