import http, { type ApiResp } from '@/api/http'

export interface CheckInBody {
  emotion: string
  rating?: number
  energy?: number
  note?: string
}

export const checkinApi = {
  month: <T = { items: unknown[]; today: unknown }>(params: { month: string }) =>
    http.get<ApiResp<T>>('/mood-check-ins', { params }).then(r => r.data.data),
  streak: <T = { current: number; longest: number; totalDays: number; makeupAvailable: boolean }>() =>
    http.get<ApiResp<T>>('/mood-check-ins/streak').then(r => r.data.data),
  create: (body: CheckInBody) => http.post('/mood-check-ins', body).then(() => undefined),
  makeup: (body: CheckInBody, params: { date: string }) =>
    http.post('/mood-check-ins/makeup', body, { params }).then(() => undefined),
}
