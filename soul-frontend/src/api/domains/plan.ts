import http, { type ApiResp } from '@/api/http'

export const planApi = {
  active: <T = { plan: unknown }>() => http.get<ApiResp<T>>('/plans/active').then(r => r.data.data),
  list: <T = { items: unknown[] }>() => http.get<ApiResp<T>>('/plans').then(r => r.data.data),
  detail: <T>(id: number | string) => http.get<ApiResp<T>>(`/plans/${id}`).then(r => r.data.data),
  adapt: <T = unknown>(id: number | string, action: string) =>
    http.post<ApiResp<T>>(`/plans/${id}/adapt`, { action }).then(r => r.data.data),
  skipToday: <T = unknown>(id: number | string) =>
    http.post<ApiResp<T>>(`/plans/${id}/skip-today`).then(r => r.data.data),
}
