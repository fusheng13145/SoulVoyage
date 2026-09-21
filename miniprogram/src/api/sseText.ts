import { API_BASE, LOGIN_PAGE } from '@/config'
import { apiError, getToken, isAuthEntry, refreshOnce } from '@/api/http'

/**
 * 逐轮流式的端侧替身。
 *
 * 小程序没有 EventSource，`enableChunked` 又只在真微信上可用（H5 侧还是另一套 API），
 * 所以这里不追求逐字打字机：把整段 SSE 响应收完再解析，取最后一个 turn_done 作为权威结果。
 * 换来的是零新端点、零后端分支——Web 端与小程序吃的是同一个流式协议。
 * 代价写在明面上：这一段回复要等模型说完才出现（Mock 下 <1s，真实供应商按 M10 口径 p95 约 300ms 首包）。
 */
export interface SseEvent {
  event: string
  data: Record<string, unknown>
}

export async function postStream(path: string, body: unknown): Promise<SseEvent[]> {
  let raw = await send(path, body, getToken())
  // 与 REST 同一套会话续期：401 先刷一次再重发，刷不动就回登录页
  if (raw.status === 401 && !isAuthEntry(path) && (await refreshOnce()))
    raw = await send(path, body, getToken())
  else if (raw.status === 401 && !isAuthEntry(path)) uni.reLaunch({ url: LOGIN_PAGE })
  const events = parseSse(raw.text)
  const err = events.find(e => e.event === 'error')
  if (err) throw apiError(String(err.data.msg || '这一句没答上来'), Number(err.data.code ?? 0))
  if (!events.some(e => e.event === 'turn_done'))
    // 流断了不等于回合没发生：服务端已落库，页面可以再用历史接口把这一轮读回来
    throw apiError('这一段回复没传完，重新进入即可看到')
  return events
}

function send(path: string, body: unknown, token: string): Promise<{ status: number; text: string }> {
  return new Promise(resolve => {
    uni.request({
      url: API_BASE + path,
      method: 'POST',
      data: body as Record<string, unknown>,
      timeout: 65000, // 后端 SseEmitter 是 60s，客户端多留 5s 收尾巴
      dataType: 'text', // 别让端侧尝试 JSON.parse：text/event-stream 一定 parse 失败
      header: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream', // 不显式声明的话，内容协商会把请求要成 JSON
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      success: res => resolve({ status: res.statusCode, text: String(res.data ?? '') }),
      fail: () => resolve({ status: 0, text: '' }),
    })
  })
}

/** SSE 帧：`event: xxx` + `data: {...}`，块间空行分隔 */
export function parseSse(text: string): SseEvent[] {
  const out: SseEvent[] = []
  for (const block of text.split(/\r?\n\r?\n/)) {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (!dataLines.length) continue
    try {
      out.push({ event, data: JSON.parse(dataLines.join('\n')) as Record<string, unknown> })
    } catch {
      /* 半截帧（断流时会有）直接丢弃：权威结果只认完整 JSON 帧 */
    }
  }
  return out
}
