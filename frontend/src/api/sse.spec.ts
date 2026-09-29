import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import http from './http'
import { postSse, streamTask, type SseEvent } from './sse'

vi.mock('./http', () => ({ default: { get: vi.fn() }, TOKEN_KEY: 'sv_access' }))

const get = vi.mocked(http.get)

/** 把若干文本片段伪装成带 ReadableStream 的 SSE 响应 */
function sseResponse(frames: string[], ok = true, status = 200) {
  const encoder = new TextEncoder()
  let i = 0
  return {
    ok,
    status,
    body: {
      getReader: () => ({
        read: async () =>
          i < frames.length
            ? { done: false, value: encoder.encode(frames[i++]) }
            : { done: true, value: undefined },
      }),
    },
  } as unknown as Response
}

describe('api/sse SSE 流解析与可靠重连', () => {
  beforeEach(() => {
    get.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('postSse 解析 event/data/id，并把事件按序交给回调', async () => {
    const received: SseEvent[] = []
    const frames = [
      'event: delta\n',
      'data: {"text":"你好"}\n',
      '\n',
      'event: done\n',
      'data: {"status":"SUCCESS"}\n',
      '\n',
    ]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)))

    await postSse('/x', { a: 1 }, e => received.push(e))

    expect(received).toEqual([
      { event: 'delta', data: { text: '你好' } },
      { event: 'done', data: { status: 'SUCCESS' } },
    ])
  })

  it('postSse 跨 chunk 分片的行也能正确拼接（帧边界不对齐）', async () => {
    const received: SseEvent[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(['event: del', 'ta\ndata: {"n":1}\n\n'])))

    await postSse('/x', {}, e => received.push(e))

    expect(received).toEqual([{ event: 'delta', data: { n: 1 } }])
  })

  it('非 JSON 的数据行被静默忽略，不影响后续帧', async () => {
    const received: SseEvent[] = []
    const frames = ['event: delta\n', 'data: 这不是 JSON\n', 'data: {"ok":true}\n', '\n']
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)))

    await postSse('/x', {}, e => received.push(e))

    expect(received).toEqual([{ event: 'delta', data: { ok: true } }])
  })

  it('响应非 2xx 或缺失 body 时直接抛错（不静默成功）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([], false, 500)))

    await expect(postSse('/x', {}, () => {})).rejects.toThrow('SSE 连接失败: 500')
  })

  it('streamTask 首次即拿到终态事件时不再重连', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(['event: done\ndata: {"status":"SUCCESS"}\n\n']))
    vi.stubGlobal('fetch', fetchMock)

    const events: SseEvent[] = []
    await streamTask('T-1', e => events.push(e))

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(events.at(-1)?.event).toBe('done')
    expect(get).not.toHaveBeenCalled()
  })

  it('断线重连耗尽后降级为轮询，命中终态时补齐 done 事件', async () => {
    vi.useFakeTimers()
    // 首次连接即失败：进入重连循环
    const fetchMock = vi.fn().mockRejectedValue(new Error('socket hang up'))
    vi.stubGlobal('fetch', fetchMock)
    get.mockResolvedValue({
      data: { data: { status: 'SUCCESS', payload: { reportId: 42 } } },
    } as never)

    const events: SseEvent[] = []
    const done = streamTask('T-2', e => events.push(e))
    await vi.runAllTimersAsync()
    await done

    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(events.at(-1)).toEqual({
      event: 'done',
      data: { status: 'SUCCESS', payload: { reportId: 42 } },
    })
  })

  it('轮询始终拿不到终态时以 error 事件收尾（给用户明确话术）', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    get.mockResolvedValue({ data: { data: { status: 'RUNNING', payload: null } } } as never)

    const events: SseEvent[] = []
    const done = streamTask('T-3', e => events.push(e))
    await vi.runAllTimersAsync()
    await done

    expect(events.at(-1)?.event).toBe('error')
    expect(String((events.at(-1)?.data as { message: string }).message)).toContain('超时')
  })

  it('携带 AbortSignal 中止时抛出 aborted，而不是当作网络抖动重试', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('aborted')))

    await expect(streamTask('T-4', () => {}, controller.signal)).rejects.toThrow('aborted')
  })
})
