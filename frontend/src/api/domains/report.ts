import http, { type ApiResp } from '@/api/http'

export interface ReportListParams {
  page: number
  size: number
  type?: string
  starred?: boolean
}

export const reportApi = {
  list: <T>(params: ReportListParams) => http.get<ApiResp<T>>('/reports', { params }).then(r => r.data.data),
  detail: <T>(id: number | string) => http.get<ApiResp<T>>(`/reports/${id}`).then(r => r.data.data),
  star: (id: number | string, starred: boolean) =>
    http.post<ApiResp<{ starred: boolean }>>(`/reports/${id}/star`, { starred }).then(r => r.data.data),
  feedback: (id: number | string, body: { rating: string; note?: string }) =>
    http.post(`/reports/${id}/feedback`, body).then(() => undefined),
}
