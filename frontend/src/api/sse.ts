import http, { TOKEN_KEY, type ApiResp, type Json } from './http'

export interface SseEvent {
  event: string
  data: Json
}

interface ConsumeResult {
  terminal: boolean
  lastId: string | null
}

const TERMINAL_EVENTS = new Set(['done', 'error'])

function consume(res: Response, onEvent: (e: SseEvent) => void): Promise<ConsumeResult> {
  return (async () => {
    if (!res.ok || !res.body) throw new Error(`SSE 连接失败: ${res.status}`)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let current = ''
    let lastId: string | null = null
    let terminal = false
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let nl: number
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).replace(/\r$/, '')
        buf = buf.slice(nl + 1)
        if (line.startsWith('event:')) current = line.slice(6).trim()
        else if (line.startsWith('id:')) lastId = line.slice(3).trim() || lastId
        else if (line.startsWith('data:')) {
          try {
            const data = JSON.parse(line.slice(5).trim())
            if (TERMINAL_EVENTS.has(current)) terminal = true
            onEvent({ event: current, data })
          } catch {
            /* 忽略非 JSON 行（心跳注释帧等） */
          }
        }
      }
    }
    return { terminal, lastId }
  })()
}

/** 请求体驱动的流式接口（如模拟训练逐轮 NPC 回复）：一次性流，无任务重放语义 */
export async function postSse(
  path: string,
  body: unknown,
  onEvent: (e: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`/api/v1${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) || ''}`,
    },
    body: JSON.stringify(body),
    signal,
  })
  await consume(res, onEvent)
}

const delay = (ms: number) => new Promise(r => setTimeout(r, ms))

/**
 * SSE via fetch + ReadableStream（EventSource 无法携带 Authorization 头）。
 * S4 可靠流：记录事件 id，断线带 Last-Event-ID 重连（服务端环形缓冲补发），
 * 连续 3 次重连仍拿不到终态则降级为轮询 GET /tasks/{taskNo}。
 */
export async function streamTask(
  taskNo: string,
  onEvent: (e: SseEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  let lastId: string | null = null
  let terminal = false
  for (let attempt = 0; attempt <= 3 && !terminal; attempt++) {
    if (attempt > 0) await delay(800 * attempt)
    try {
      const headers: Record<string, string> = {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) || ''}`,
      }
      if (lastId) headers['Last-Event-ID'] = lastId
      const res = await fetch(`/api/v1/tasks/${taskNo}/stream`, { headers, signal })
      const r = await consume(res, onEvent)
      terminal = r.terminal
      if (r.lastId) lastId = r.lastId
    } catch {
      if (signal?.aborted) throw new Error('aborted')
      // 网络抖动：进入下一次重连
    }
  }
  if (!terminal) await pollFallback(taskNo, onEvent)
}

/** 降级轮询：以 done 事件形态补齐终态，payload 与 SSE 的 done.data 对齐 */
async function pollFallback(taskNo: string, onEvent: (e: SseEvent) => void): Promise<void> {
  for (let i = 0; i < 60; i++) {
    try {
      const { data } = await http.get<ApiResp<{ status: string; payload: unknown }>>(`/tasks/${taskNo}`)
      const st = data.data.status
      if (st === 'SUCCESS' || st === 'PARTIAL_SUCCESS' || st === 'FAILED' || st === 'CANCELED') {
        onEvent({ event: 'done', data: { status: st, payload: data.data.payload } })
        return
      }
    } catch {
      /* 轮询失败继续下一轮 */
    }
    await delay(2000)
  }
  onEvent({ event: 'error', data: { message: '任务状态获取超时，请稍后在日记本查看结果' } })
}
