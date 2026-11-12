import http, { type ApiResp } from '@/api/http'

export const achievementApi = {
  wall: <T = { items: unknown[]; unlockedCount: number; totalCount: number }>() =>
    http.get<ApiResp<T>>('/achievements').then(r => r.data.data),
}
