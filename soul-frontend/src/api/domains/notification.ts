import http, { type ApiResp } from '@/api/http'

export interface NotificationListParams {
  page: number
  size: number
  unreadOnly?: boolean
}

export const notificationApi = {
  list: <T = { items: unknown[]; total: number; unreadCount: number }>(params: NotificationListParams) =>
    http.get<ApiResp<T>>('/notifications', { params }).then(r => r.data.data),
  read: (id: number | string) => http.post(`/notifications/${id}/read`, {}).then(() => undefined),
  readAll: () => http.post('/notifications/read-all', {}).then(() => undefined),
  getPrefs: <T>() => http.get<ApiResp<T>>('/preferences').then(r => r.data.data),
  putPrefs: <T>(body: object) => http.put<ApiResp<T>>('/preferences', body).then(r => r.data.data),
}
