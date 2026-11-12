import http, { type ApiResp } from '@/api/http'

export const readingApi = {
  today: <T>() => http.get<ApiResp<T>>('/readings/today').then(r => r.data.data),
  favorites: <T = unknown[]>() => http.get<ApiResp<T>>('/readings/favorites').then(r => r.data.data),
  toggleFavorite: (body: { type: string; refCode: string }) =>
    http.post<ApiResp<{ favorited: boolean }>>('/readings/favorites', body).then(r => r.data.data),
}
