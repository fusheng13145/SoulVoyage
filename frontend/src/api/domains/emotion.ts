import http, { type ApiResp } from '@/api/http'

export interface TrajectoryParams {
  from: string
  to: string
}

export interface SelfRatingBody {
  emotion: string
  valence: number
  intensity: number
  date: string
}

export const emotionApi = {
  trajectory: <T = { points: unknown[] }>(params: TrajectoryParams) =>
    http.get<ApiResp<T>>('/emotions/trajectory', { params }).then(r => r.data.data),
  profile: <T = { crisisMode: boolean; weeks: unknown[] }>() =>
    http.get<ApiResp<T>>('/emotions/profile').then(r => r.data.data),
  selfRating: (body: SelfRatingBody) => http.post('/emotions/self-rating', body).then(() => undefined),
}
