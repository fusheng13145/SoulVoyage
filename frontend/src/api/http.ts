import axios from 'axios'
import router from '../router'

export interface ApiResp<T> {
  code: number
  msg: string
  data: T
  traceId: string
}

export const TOKEN_KEY = 'sv_access'
export const REFRESH_KEY = 'sv_refresh'

const http = axios.create({ baseURL: '/api/v1', timeout: 30000 })

http.interceptors.request.use((cfg) => {
  const t = localStorage.getItem(TOKEN_KEY)
  if (t) cfg.headers.Authorization = `Bearer ${t}`
  return cfg
})

/** 服务端开启 refresh 轮换 + 重用检测后，同一旧 refresh 用两次会被判失窃吊销全部会话——并发 401 必须共享一次刷新 */
let refreshing: Promise<boolean> | null = null
async function refreshOnce(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const { data } = await axios.post<ApiResp<{ accessToken: string; refreshToken: string }>>(
          '/api/v1/auth/refresh',
          { refreshToken: localStorage.getItem(REFRESH_KEY) },
        )
        localStorage.setItem(TOKEN_KEY, data.data.accessToken)
        localStorage.setItem(REFRESH_KEY, data.data.refreshToken)
        return true
      } catch {
        return false
      } finally {
        setTimeout(() => { refreshing = null }, 0)
      }
    })()
  }
  return refreshing
}

http.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResp<unknown>
    if (body && body.code !== 0) {
      return Promise.reject(Object.assign(new Error(body.msg || '请求失败'), { code: body.code }))
    }
    return res
  },
  async (err) => {
    const status = err.response?.status
    const original = err.config
    // S3：后端已返回真实 HTTP 状态码；业务错误信息在响应体 code/msg 里，统一转成 Error(msg)
    const body = err.response?.data as ApiResp<unknown> | undefined
    const isAuthEntry = original?.url?.includes('/auth/')   // 登录/注册/刷新失败不触发全局登出
    if (status === 401 && !isAuthEntry && !original?._retried && localStorage.getItem(REFRESH_KEY)) {
      if (original) original._retried = true
      if (await refreshOnce()) return http(original)
    }
    if (status === 401 && !isAuthEntry) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
      router.push('/login')
    }
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(Object.assign(new Error(body.msg || '请求失败'), { code: body.code }))
    }
    return Promise.reject(err)
  },
)

export default http
