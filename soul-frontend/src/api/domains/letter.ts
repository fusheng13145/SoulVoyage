import http, { type ApiResp } from '@/api/http'

export const letterApi = {
  list: <T = { items: unknown[]; total: number }>() =>
    http.get<ApiResp<T>>('/letters').then(r => r.data.data),
}
