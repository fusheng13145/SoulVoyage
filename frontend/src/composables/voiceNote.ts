import { onUnmounted, ref } from 'vue'
import http, { type ApiResp } from '@/api/http'

/** 与后端 VoiceDiaryController.MAX_DURATION_MS 同一口径：一段最多 90 秒 */
export const MAX_VOICE_MS = 90_000
/** 短于这个时长不发起转写：半秒的呼吸声只会换来一句"没听清"，白花一次上行 */
const MIN_VOICE_MS = 700

/**
 * M11 语音日记：录音 → 上传转写 → 把文本交回页面校对。
 * 这里只管"录与转"，不碰草稿——语音不该覆盖用户已经敲下的字，合并策略归页面定。
 */
export function useVoiceNote(onTranscript: (text: string, durationMs: number) => void) {
  const supported =
    typeof window !== 'undefined' &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof window.MediaRecorder !== 'undefined'

  const recording = ref(false)
  /** 开麦握手期（权限弹窗/设备初始化）：此间按钮必须不可点——
   *  否则再点一次会重复 getUserMedia 并让前一路 stream 的轨道无人 release（麦克风指示灯常亮），
   *  而在握手期点的"停止"会因为 recorder 还没建出来而被整个吞掉。 */
  const starting = ref(false)
  const transcribing = ref(false)
  const elapsedMs = ref(0)
  const error = ref('')

  let recorder: MediaRecorder | null = null
  let stream: MediaStream | null = null
  let chunks: Blob[] = []
  let timer: number | undefined
  let startedAt = 0
  let discard = false

  function release() {
    if (timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
    stream?.getTracks().forEach(t => t.stop())
    stream = null
    recording.value = false
  }

  async function transcribe(blob: Blob, durationMs: number) {
    transcribing.value = true
    try {
      const form = new FormData()
      form.append('file', blob, 'voice')
      form.append('durationMs', String(Math.round(durationMs)))
      const { data } = await http.post<ApiResp<{ text: string; durationMs: number }>>(
        '/diaries/voice-transcriptions',
        form,
      )
      onTranscript(data.data.text, data.data.durationMs)
    } catch (e) {
      // 后端的中文话术（太大/不是音频/转写失败）直接透传，前端不再编第二套说辞
      error.value = (e as Error).message || '转写失败了，先手写一段吧'
    } finally {
      transcribing.value = false
    }
  }

  async function start() {
    if (!supported || recording.value || starting.value || transcribing.value) return
    error.value = ''
    starting.value = true
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      error.value = '没拿到麦克风权限——到浏览器设置里允许后再试，或者先手写'
      return
    } finally {
      starting.value = false
    }
    chunks = []
    discard = false
    recorder = new MediaRecorder(stream)
    recorder.ondataavailable = e => {
      if (e.data.size > 0) chunks.push(e.data)
    }
    recorder.onstop = () => {
      const blob = new Blob(chunks, { type: recorder?.mimeType || 'audio/webm' })
      const dur = elapsedMs.value
      release()
      if (discard) return
      if (dur < MIN_VOICE_MS || blob.size === 0) {
        error.value = '这段太短啦，没听清——按住说完再松手'
        return
      }
      void transcribe(blob, dur)
    }
    startedAt = Date.now()
    elapsedMs.value = 0
    recording.value = true
    recorder.start()
    timer = window.setInterval(() => {
      elapsedMs.value = Math.min(MAX_VOICE_MS, Date.now() - startedAt)
      if (elapsedMs.value >= MAX_VOICE_MS) stop() // 到上限自己收口，别让用户白录最后一段
    }, 200)
  }

  function stop() {
    if (recorder && recorder.state !== 'inactive') recorder.stop()
    else release()
  }

  /** 丢弃这段：不上传、不留副本，只把麦克风还回去 */
  function cancel() {
    discard = true
    if (recorder && recorder.state !== 'inactive') recorder.stop()
    else release()
  }

  onUnmounted(release)

  return { supported, recording, starting, transcribing, elapsedMs, error, start, stop, cancel }
}
