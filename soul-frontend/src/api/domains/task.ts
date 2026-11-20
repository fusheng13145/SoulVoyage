import http, { type ApiResp } from '@/api/http'

export interface SubmitTaskBody {
  pipelineCode: string
  payload: Record<string, unknown>
  clientReqId?: string
}

export interface TaskItem {
  taskNo: string
  pipelineCode: string
  status: string
  createdAt: string
  finishedAt: string
  errorMsg: string | null
}

/** H-01 步骤时间线：与后端 TaskTimelineStep 同形 */
export interface TaskStep {
  stepSeq: number
  agent: string
  stepCode: string
  status: string
  attempt: number
  costMs: number | null
  errorMsg: string | null
  createdAt: string
}

export const taskApi = {
  submit: (body: SubmitTaskBody) =>
    http.post<ApiResp<{ taskNo: string }>>('/tasks', body).then(r => r.data.data),
  list: <T = { items: TaskItem[] }>(params: {
    page: number
    size: number
    pipelineCode?: string
    status?: string
  }) => http.get<ApiResp<T>>('/tasks', { params }).then(r => r.data.data),
  get: <T = { status: string; payload: unknown }>(taskNo: string) =>
    http.get<ApiResp<T>>(`/tasks/${taskNo}`).then(r => r.data.data),
  steps: (taskNo: string) => http.get<ApiResp<TaskStep[]>>(`/tasks/${taskNo}/steps`).then(r => r.data.data),
  retry: (taskNo: string) =>
    http.post<ApiResp<{ taskNo: string; status: string }>>(`/tasks/${taskNo}/retry`).then(r => r.data.data),
}
