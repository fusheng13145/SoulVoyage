import { beforeEach, describe, expect, it, vi } from 'vitest'

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

import { REFRESH_KEY, TOKEN_KEY } from './http'

/** 让 refreshOnce 的 finally 里那个 setTimeout(...,0) 有机会跑完，重置模块级 refreshing */
const flushMacrotask = () => new Promise(r => setTimeout(r, 0))

describe('api/http 请求与响应拦截器', () => {
  beforeEach(() => {
    localStorage.clear()
    H.routerPush.mockClear()
    H.refreshPost.mockReset()
    expect(H.handlers.req, '拦截器应已注册').toBeTypeOf('function')
  })

  it('请求拦截器：有 token 时注入 Bearer 头', () => {
    localStorage.setItem(TOKEN_KEY, 'tk-1')
    const cfg: ReqCfg = { headers: {} }
    H.handlers.req!(cfg)
    expect(cfg.headers.Authorization).toBe('Bearer tk-1')
  })

  it('请求拦截器：无 token 时不注入 Authorization', () => {
    const cfg: ReqCfg = { headers: {} }
    H.handlers.req!(cfg)
    expect(cfg.headers.Authorization).toBeUndefined()
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

  it('401 且非 auth 路径：刷新成功后带新 token 重放原请求', async () => {
    localStorage.setItem(REFRESH_KEY, 'rt-1')
    H.refreshPost.mockResolvedValue({
      data: { data: { accessToken: 'at-2', refreshToken: 'rt-2' } },
    })
    const original: Record<string, unknown> = { url: '/diaries' }

    const result = await H.handlers.resErr!({ response: { status: 401 }, config: original })

    expect(H.refreshPost).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('at-2')
    expect(localStorage.getItem(REFRESH_KEY)).toBe('rt-2')
    expect(original._retried).toBe(true)
    expect(result).toEqual(original)
    await flushMacrotask()
  })

  it('401 且刷新失败：清空令牌并跳登录页', async () => {
    localStorage.setItem(TOKEN_KEY, 'at-1')
    localStorage.setItem(REFRESH_KEY, 'rt-1')
    H.refreshPost.mockRejectedValue(new Error('refresh rejected'))

    await expect(
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/diaries' } }),
    ).rejects.toBeTruthy()

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(H.routerPush).toHaveBeenCalledWith('/login')
    await flushMacrotask()
  })

  it('auth 路径的 401 不触发全局登出（登录失败不该把用户踢出去）', async () => {
    localStorage.setItem(REFRESH_KEY, 'rt-1')

    await expect(
      H.handlers.resErr!({ response: { status: 401 }, config: { url: '/auth/login' } }),
    ).rejects.toBeTruthy()

    expect(H.routerPush).not.toHaveBeenCalled()
    expect(localStorage.getItem(REFRESH_KEY)).toBe('rt-1')
    await flushMacrotask()
  })

  it('已重放过的请求再 401 时不进入刷新循环', async () => {
    localStorage.setItem(REFRESH_KEY, 'rt-1')
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
})
