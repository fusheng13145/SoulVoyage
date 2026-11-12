import http, { type ApiResp } from '@/api/http'

export interface OpenSimulationBody {
  sceneCode: string
  difficulty: string
}

export const simulationApi = {
  list: <T = { items: unknown[] }>(params: { page: number; size: number }) =>
    http.get<ApiResp<T>>('/simulations', { params }).then(r => r.data.data),
  stats: <T = unknown[]>() => http.get<ApiResp<T>>('/simulations/stats').then(r => r.data.data),
  detail: <T>(id: number | string) => http.get<ApiResp<T>>(`/simulations/${id}`).then(r => r.data.data),
  open: <T = { simulateId: string }>(body: OpenSimulationBody) =>
    http.post<ApiResp<T>>('/simulations', body).then(r => r.data.data),
  interrupt: (id: number | string) => http.post(`/simulations/${id}/interrupt`, {}).then(() => undefined),
  finish: (id: number | string) =>
    http.post<ApiResp<{ taskNo: string }>>(`/simulations/${id}/finish`, {}).then(r => r.data.data),
}
