import http, { type ApiResp } from '@/api/http'

export const companionApi = {
  active: <T = { sessionId: string; segmentNo: number; turns: number } | null>() =>
    http.get<ApiResp<T>>('/companion/active').then(r => r.data.data),
  open: <T = { sessionId: string }>() =>
    http.post<ApiResp<T>>('/companion/sessions', {}).then(r => r.data.data),
  history: <T = { items: unknown[]; total: number }>(params: { page: number; size: number }) =>
    http.get<ApiResp<T>>('/companion/sessions', { params }).then(r => r.data.data),
  transcript: <T>(id: number | string) =>
    http.get<ApiResp<T>>(`/companion/sessions/${id}`).then(r => r.data.data),
  noAnalyze: (turnId: number | string) =>
    http.post(`/companion/turns/${turnId}/no-analyze`, {}).then(() => undefined),
  end: (id: number | string) =>
    http.post<ApiResp<{ taskNo: string }>>(`/companion/sessions/${id}/end`, {}).then(r => r.data.data),
}
