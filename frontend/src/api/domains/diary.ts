import http, { type ApiResp } from '@/api/http'

export interface DiaryListParams {
  q?: string
  page: number
  size: number
}

export const diaryApi = {
  list: <T>(params: DiaryListParams) => http.get<ApiResp<T>>('/diaries', { params }).then(r => r.data.data),
  detail: <T>(id: number | string) => http.get<ApiResp<T>>(`/diaries/${id}`).then(r => r.data.data),
  update: (id: number | string, body: { content: string; moodSelfRating?: number | null }) =>
    http.put(`/diaries/${id}`, body).then(() => undefined),
  remove: (id: number | string) => http.delete(`/diaries/${id}`).then(() => undefined),
  reanalyze: (id: number | string) =>
    http.post<ApiResp<{ taskNo: string }>>(`/diaries/${id}/reanalyze`).then(r => r.data.data),
  getDraft: () => http.get<ApiResp<{ content: string }>>('/diaries/draft').then(r => r.data.data),
  saveDraft: (content: string) => http.put('/diaries/draft', { content }).then(() => undefined),
  deleteDraft: () => http.delete('/diaries/draft').then(() => undefined),
}
