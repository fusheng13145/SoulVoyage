import { TOKEN_KEY } from './http'

export interface SseEvent {
  event: string
  data: any
}

/** SSE via fetch + ReadableStream（EventSource 无法携带 Authorization 头） */
export async function streamTask(
  taskNo: string,
  onEvent: (e: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`/api/v1/tasks/${taskNo}/stream`, {
    headers: {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) || ''}`,
    },
    signal,
  })
  if (!res.ok || !res.body) throw new Error(`SSE 连接失败: ${res.status}`)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let current = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let nl: number
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '')
      buf = buf.slice(nl + 1)
      if (line.startsWith('event:')) current = line.slice(6).trim()
      else if (line.startsWith('data:')) {
        try {
          onEvent({ event: current, data: JSON.parse(line.slice(5).trim()) })
        } catch { /* 忽略非 JSON 行 */ }
      }
    }
  }
}
