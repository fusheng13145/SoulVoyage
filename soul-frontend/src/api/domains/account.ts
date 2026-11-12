import http, { type ApiResp } from '@/api/http'

export const accountApi = {
  requestDeletion: (password: string) => http.post('/users/me/delete', { password }).then(() => undefined),
  cancelDeletion: () => http.post('/users/me/delete/cancel').then(() => undefined),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.put('/users/me/password', { oldPassword, newPassword }).then(() => undefined),
  createDataExport: () =>
    http.get<ApiResp<{ fileId: string }>>('/users/me/data-export').then(r => r.data.data),
  consumeDataExport: <T = Record<string, unknown>>(fileId: string) =>
    http.post<ApiResp<T>>(`/users/me/data-export/${fileId}`).then(r => r.data.data),
}
