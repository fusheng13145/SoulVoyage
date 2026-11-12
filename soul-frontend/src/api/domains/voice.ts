import http, { type ApiResp } from '@/api/http'

export const voiceApi = {
  transcribe: (form: FormData) =>
    http
      .post<ApiResp<{ text: string; durationMs: number }>>('/diaries/voice-transcriptions', form)
      .then(r => r.data.data),
}
