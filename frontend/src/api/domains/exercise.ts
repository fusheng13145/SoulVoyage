import http, { type ApiResp } from '@/api/http'

export interface ExerciseRecordBody {
  exerciseId: string
  planId?: string
  planItemSeq?: number
  scheduledDate?: string
  durationActual?: number
  ratingStar?: number
  feedback?: string
  completed?: boolean
}

export const exerciseApi = {
  list: <T = unknown[]>() => http.get<ApiResp<T>>('/exercises').then(r => r.data.data),
  records: <T = unknown[]>(params: { limit: number }) =>
    http.get<ApiResp<T>>('/exercise-records', { params }).then(r => r.data.data),
  record: (body: ExerciseRecordBody) => http.post('/exercise-records', body).then(() => undefined),
  cognitiveWriting: (body: Record<string, unknown>) =>
    http.post<ApiResp<{ taskNo: string }>>('/exercises/cognitive-writing', body).then(r => r.data.data),
}
