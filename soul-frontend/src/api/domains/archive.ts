import http, { type ApiResp } from '@/api/http'

export const archiveApi = {
  summary: <T>() => http.get<ApiResp<T>>('/archive/summary').then(r => r.data.data),
  createExport: () => http.post<ApiResp<{ fileId: string }>>('/archive/export', {}).then(r => r.data.data),
  claimExport: <T>(fileId: string) =>
    http.get<ApiResp<T>>(`/archive/export/${fileId}`).then(r => r.data.data),
}
