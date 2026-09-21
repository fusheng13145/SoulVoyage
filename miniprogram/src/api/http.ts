import { API_BASE, LOGIN_PAGE, REFRESH_KEY, TOKEN_KEY } from '@/config'

/** 后端统一信封：成功码是 0，不是 200 —— 沿用 Web 端口径，业务错误看 code、传输错误看 status */
export interface ApiResp<T> {
  code: number
  msg: string
  data: T
  traceId: string
}

/** 抛给页面层的错误：msg 已是后端给的中文话术，页面直接展示即可，不再自己编文案 */
export interface ApiError extends Error {
  code?: number
  status?: number
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

export function getToken(): string {
  return (uni.getStorageSync(TOKEN_KEY) as string) || ''
}
export function getRefreshToken(): string {
  return (uni.getStorageSync(REFRESH_KEY) as string) || ''
}
export function setTokens(t: { accessToken: string; refreshToken: string }) {
  uni.setStorageSync(TOKEN_KEY, t.accessToken)
  uni.setStorageSync(REFRESH_KEY, t.refreshToken)
}
export function clearTokens() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(REFRESH_KEY)
}

export function apiError(msg: string, code?: number, status?: number): ApiError {
  return Object.assign(new Error(msg), { code, status }) as ApiError
}

interface RawResult<T> {
  status: number
  body?: ApiResp<T>
}

function rawCall<T>(method: Method, path: string, body?: unknown): Promise<RawResult<T>> {
  return new Promise(resolve => {
    const token = getToken()
    uni.request({
      url: API_BASE + path,
      method,
      data: body as Record<string, unknown> | undefined,
      timeout: 30000,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      // 非 2xx 也走 success：后端把业务码写在响应体里，401/403/429 同样要读出来才认得出是谁的错
      success: res => {
        const parsed: unknown = res.data
        const envelope = typeof parsed === 'string' || parsed == null ? undefined : (parsed as ApiResp<T>)
        resolve({ status: res.statusCode, body: envelope })
      },
      fail: () => resolve({ status: 0 }),
    })
  })
}

/** 服务端开了 refresh 轮换 + 重用检测：同一个旧 refresh 用两次会被判失窃吊销全部会话，
 *  所以并发 401 必须共享同一次刷新，而不是各自去换票。 */
let refreshing: Promise<boolean> | null = null
export async function refreshOnce(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const rt = getRefreshToken()
      if (!rt) return false
      const r = await rawCall<{ accessToken: string; refreshToken: string }>('POST', '/auth/refresh', {
        refreshToken: rt,
      })
      if (r.status !== 200 || !r.body || r.body.code !== 0) return false
      setTokens(r.body.data)
      return true
    })().finally(() => {
      // 本轮结束即清空，下一轮 401 才允许再刷一次（否则吊销后的死会话会被无限重试）
      refreshing = null
    })
  }
  return refreshing
}

/** 登录/注册/刷新自己的 401 不该触发"全局掉线→reLaunch"，否则输错口令会把人踢回登录页 */
export function isAuthEntry(path: string): boolean {
  return path.startsWith('/auth/')
}

export async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  let r = await rawCall<T>(method, path, body)
  if (r.status === 0) throw apiError('网络不太顺，检查一下网络再试')

  if (r.status === 401 && !isAuthEntry(path) && getRefreshToken() && (await refreshOnce())) {
    r = await rawCall<T>(method, path, body) // 只重试一次：再 401 就是真过期
  }
  if (r.status === 401 && !isAuthEntry(path)) {
    clearTokens()
    uni.reLaunch({ url: LOGIN_PAGE })
  }

  const envelope = r.body
  if (!envelope || typeof envelope.code !== 'number') {
    throw apiError(`服务暂时没答上来（${r.status}）`, undefined, r.status)
  }
  if (envelope.code !== 0) throw apiError(envelope.msg || '操作没有成功', envelope.code, r.status)
  return envelope.data
}

export const get = <T>(path: string) => request<T>('GET', path)
export const post = <T>(path: string, body?: unknown) => request<T>('POST', path, body)
export const put = <T>(path: string, body?: unknown) => request<T>('PUT', path, body)
export const del = <T>(path: string, body?: unknown) => request<T>('DELETE', path, body)

/** 可选读：失败即 null。首屏聚合类请求用它，避免一个次要块失败把整页打死 */
export async function readOr<T>(p: Promise<T>): Promise<T | null> {
  try {
    return await p
  } catch {
    return null
  }
}
