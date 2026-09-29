import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '@/api/http'
import { MAX_VOICE_MS, MIN_VOICE_MS, useVoiceNote } from './voiceNote'

vi.mock('@/api/http', () => ({ default: { post: vi.fn() } }))

const post = vi.mocked(http.post)

/** 最小可用的 MediaRecorder 替身：记录实例、可手动触发数据与停止 */
class FakeRecorder {
  state = 'inactive'
  mimeType = 'audio/webm'
  ondataavailable: ((e: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null

  constructor(public stream: unknown) {}

  start() {
    this.state = 'recording'
  }

  /** 默认产出一段非空数据，模拟真实录音收尾 */
  stop(emit = true) {
    this.state = 'inactive'
    if (emit) this.ondataavailable?.({ data: new Blob(['voice-bytes'], { type: 'audio/webm' }) })
    this.onstop?.()
  }
}

const stopTrack = vi.fn()
const getUserMedia = vi.fn()

/** 计时器每 200ms 触发一次，故推进量取 200 的整数倍才能得到确定读数 */
const TICK = 200

describe('composables/voiceNote 语音日记录制与转写', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    stopTrack.mockClear()
    getUserMedia.mockReset()
    getUserMedia.mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] })
    post.mockReset()

    vi.stubGlobal('MediaRecorder', FakeRecorder)
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    })
    Object.defineProperty(window, 'MediaRecorder', { configurable: true, value: FakeRecorder })
  })

  it('探测到录音能力后 supported 为真', () => {
    const v = useVoiceNote(() => {})
    expect(v.supported).toBe(true)
  })

  it('麦克风权限被拒时给出可操作话术，且不进入录音态', async () => {
    getUserMedia.mockRejectedValueOnce(new Error('NotAllowedError'))
    const v = useVoiceNote(() => {})

    await v.start()

    expect(v.error.value).toContain('麦克风权限')
    expect(v.recording.value).toBe(false)
    expect(v.starting.value).toBe(false)
  })

  it('开麦握手期间 starting 置真，结束后复位并进入录音态', async () => {
    let release!: () => void
    getUserMedia.mockReturnValueOnce(
      new Promise(r => {
        release = () => r({ getTracks: () => [{ stop: stopTrack }] })
      }),
    )
    const v = useVoiceNote(() => {})

    const pending = v.start()
    expect(v.starting.value).toBe(true)

    release()
    await pending

    expect(v.starting.value).toBe(false)
    expect(v.recording.value).toBe(true)
    expect(v.error.value).toBe('')
  })

  it('录音中重复点击 start 不会二次开麦', async () => {
    const v = useVoiceNote(() => {})
    await v.start()
    await v.start()

    expect(getUserMedia).toHaveBeenCalledTimes(1)
  })

  it('计时器按 tick 累加 elapsedMs，正常 stop 后转写成功并归还麦克风', async () => {
    const onTranscript = vi.fn()
    post.mockResolvedValueOnce({
      data: { data: { text: '今天有点累', durationMs: 1200 } },
    } as never)

    const v = useVoiceNote(onTranscript)
    await v.start()
    vi.advanceTimersByTime(6 * TICK)
    expect(v.elapsedMs.value).toBe(1200)

    v.stop()
    await vi.runAllTimersAsync()

    expect(onTranscript).toHaveBeenCalledWith('今天有点累', 1200)
    expect(stopTrack).toHaveBeenCalled()
    expect(v.recording.value).toBe(false)
    expect(v.transcribing.value).toBe(false)
  })

  it('录音时长达上限自动收口，不让用户白录最后一段', async () => {
    post.mockResolvedValueOnce({
      data: { data: { text: 'x', durationMs: MAX_VOICE_MS } },
    } as never)

    const v = useVoiceNote(() => {})
    await v.start()
    vi.advanceTimersByTime(MAX_VOICE_MS + 5 * TICK)
    await vi.runAllTimersAsync()

    expect(v.elapsedMs.value).toBe(MAX_VOICE_MS)
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('短于下限的录音只提示重录，不发起上传', async () => {
    const v = useVoiceNote(() => {})
    await v.start()
    vi.advanceTimersByTime(3 * TICK)
    expect(v.elapsedMs.value).toBeLessThan(MIN_VOICE_MS)

    v.stop()
    await vi.runAllTimersAsync()

    expect(v.error.value).toContain('太短')
    expect(post).not.toHaveBeenCalled()
  })

  it('cancel 丢弃这段：不上传、不留副本，只把麦克风还回去', async () => {
    const onTranscript = vi.fn()
    const v = useVoiceNote(onTranscript)
    await v.start()
    vi.advanceTimersByTime(10 * TICK)

    v.cancel()
    await vi.runAllTimersAsync()

    expect(post).not.toHaveBeenCalled()
    expect(onTranscript).not.toHaveBeenCalled()
    expect(stopTrack).toHaveBeenCalled()
    expect(v.error.value).toBe('')
  })

  it('转写失败时透传后端话术，供用户直接读懂', async () => {
    post.mockRejectedValueOnce(new Error('音频超过 5MB，请分段录制'))

    const v = useVoiceNote(() => {})
    await v.start()
    vi.advanceTimersByTime(10 * TICK)
    v.stop()
    await vi.runAllTimersAsync()

    expect(v.error.value).toBe('音频超过 5MB，请分段录制')
    expect(v.transcribing.value).toBe(false)
  })

  it('转写失败且后端未给话术时回落到内置提示', async () => {
    post.mockRejectedValueOnce(new Error(''))

    const v = useVoiceNote(() => {})
    await v.start()
    vi.advanceTimersByTime(10 * TICK)
    v.stop()
    await vi.runAllTimersAsync()

    expect(v.error.value).toContain('转写失败')
  })

  it('recorder 尚未建出时 stop 走 release 分支不抛错', () => {
    const v = useVoiceNote(() => {})
    expect(() => v.stop()).not.toThrow()
  })

  it('上传的 FormData 携带 file 与 durationMs 两字段，时长取整到当前读数', async () => {
    post.mockResolvedValueOnce({ data: { data: { text: 't', durationMs: 1600 } } } as never)

    const v = useVoiceNote(() => {})
    await v.start()
    vi.advanceTimersByTime(8 * TICK)
    v.stop()
    await vi.runAllTimersAsync()

    const [url, form] = post.mock.calls[0] as [string, FormData]
    expect(url).toBe('/diaries/voice-transcriptions')
    expect(form).toBeInstanceOf(FormData)
    expect(form.get('durationMs')).toBe('1600')
    expect(form.get('file')).toBeInstanceOf(Blob)
  })
})
