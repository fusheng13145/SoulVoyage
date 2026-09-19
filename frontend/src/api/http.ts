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

http.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResp<unknown>
    if (body && body.code !== 0) {
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return res
  },
  async (err) => {
    const status = err.response?.status
    const original = err.config
    if (status === 401 && !original._retried && localStorage.getItem(REFRESH_KEY)) {
      original._retried = true
      try {
        const { data } = await axios.post<ApiResp<{ accessToken: string; refreshToken: string }>>(
          '/api/v1/auth/refresh',
          { refreshToken: localStorage.getItem(REFRESH_KEY) },
        )
        localStorage.setItem(TOKEN_KEY, data.data.accessToken)
        localStorage.setItem(REFRESH_KEY, data.data.refreshToken)
        return http(original)
      } catch {
        /* fallthrough */
      }
    }
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
      router.push('/login')
    }
    return Promise.reject(err)
  },
)

export default http
