import axios from 'axios'
import router from '../router'

export interface ApiResp<T> {
  code: number
  msg: string
  data: T
  traceId: string
}

/** 后端契约载荷（闭集 schema 校验过的 Agent 产出/报告正文）：动态 JSON。
 *  全工程唯一 any 逃生口——能用 OpenAPI 生成类型或本地接口收窄的，一律收窄后再消费。 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = Record<string, any>

/** B-09 乐观锁冲突：多端同时修改同一聚合，后端返回 409 + 2003，调用方应刷新后重试 */
export const CONFLICT_CODE = 2003
export function isConflict(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { code?: number }).code === CONFLICT_CODE
}

/**
 * 令牌载体（2026-11-20 安全审查 04 项）：
 * - **access**：只放内存，页面刷新即失效，XSS 也读不到长效凭证；
 * - **refresh**：由后端下发 `HttpOnly; SameSite=Lax; Path=/api/v1/auth` Cookie，JS 完全碰不到；
 *   页面刷新后靠 `ensureSession()` 调一次 `/auth/refresh`（浏览器自动带 Cookie）把 access 换回来。
 *
 * 旧实现把两枚令牌都写 localStorage，一次 XSS 就能拿走 7 天有效的 refresh——那正是本次要消掉的风险面。
 */
let accessToken: string | null = null
/** 首次静默续期是否已尝试过：避免无会话时每个受限路由都白打一次 refresh */
let sessionProbed = false
/** 静默续期单飞（启动/路由守卫并发时只打一次） */
let probing: Promise<boolean> | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function hasSession(): boolean {
  return !!accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  sessionProbed = true
}

export function clearAccessToken(): void {
  accessToken = null
  sessionProbed = false
}

const http = axios.create({ baseURL: '/api/v1', timeout: 30000, withCredentials: true })

http.interceptors.request.use(cfg => {
  if (accessToken) cfg.headers.Authorization = `Bearer ${accessToken}`
  return cfg
})

/** 服务端开启 refresh 轮换 + 重用检测后，同一旧 refresh 用两次会被判失窃吊销全部会话——并发 401 必须共享一次刷新 */
let refreshing: Promise<boolean> | null = null

const REFRESH_LOCK = 'sv_refresh_lock'

/** B-07 跨标签互斥：有 Web Locks 时同一时刻只允许一个标签执行 refresh；不支持时退回单飞 */
function withRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = (
    navigator as Navigator & {
      locks?: { request: (name: string, cb: () => Promise<T>) => Promise<T> }
    }
  ).locks
  return locks?.request ? locks.request(REFRESH_LOCK, fn) : fn()
}

/** 真正调刷新接口：只回填 access，refresh 由响应里的 Set-Cookie 轮换，JS 不参与 */
async function doRefresh(): Promise<boolean> {
  try {
    const { data } = await axios.post<ApiResp<{ accessToken: string }>>('/api/v1/auth/refresh', null, {
      withCredentials: true,
    })
    setAccessToken(data.data.accessToken)
    return true
  } catch {
    return false
  }
}

/** 401 后的强制续期：忽略"已探测"标记（access 过期不代表 Cookie 失效） */
function refreshOnce(): Promise<boolean> {
  if (!refreshing) {
    refreshing = withRefreshLock(doRefresh).finally(() => {
      setTimeout(() => {
        refreshing = null
      }, 0)
    })
  }
  return refreshing
}

/**
 * 启动/进入受限路由时的静默续期：内存里没有 access 才尝试，且每个会话只尝试一次。
 * 返回是否已拿到 access（拿不到就说明 Cookie 也没有，调用方该跳登录）。
 */
export function ensureSession(): Promise<boolean> {
  if (accessToken) return Promise.resolve(true)
  if (sessionProbed) return Promise.resolve(false)
  if (!probing) {
    probing = withRefreshLock(doRefresh).then(ok => {
      sessionProbed = true
      return ok
    })
  }
  const current = probing
  void current.finally(() => {
    if (probing === current) probing = null
  })
  return current
}

/**
 * B-07：带鉴权的 fetch（SSE 等无法走 axios 的场景共用）。
 * 401 时复用统一单飞刷新并重试一次；仍失败则清空内存会话，由调用方决定跳转/降级。
 */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const send = () => {
    const headers = new Headers(init.headers)
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    return fetch(input, { ...init, headers })
  }
  let res = await send()
  if (res.status === 401) {
    if (await refreshOnce()) res = await send()
    if (res.status === 401) clearAccessToken()
  }
  return res
}

http.interceptors.response.use(
  res => {
    const body = res.data as ApiResp<unknown>
    if (body && body.code !== 0) {
      return Promise.reject(Object.assign(new Error(body.msg || '请求失败'), { code: body.code }))
    }
    return res
  },
  async err => {
    const status = err.response?.status
    const original = err.config
    // S3：后端已返回真实 HTTP 状态码；业务错误信息在响应体 code/msg 里，统一转成 Error(msg)
    const body = err.response?.data as ApiResp<unknown> | undefined
    const isAuthEntry = original?.url?.includes('/auth/') // 登录/注册/刷新失败不触发全局登出
    if (status === 401 && !isAuthEntry && !original?._retried) {
      if (original) original._retried = true
      if (await refreshOnce()) return http(original)
    }
    if (status === 401 && !isAuthEntry) {
      clearAccessToken()
      router.push('/login')
    }
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(Object.assign(new Error(body.msg || '请求失败'), { code: body.code }))
    }
    return Promise.reject(err)
  },
)

export default http
