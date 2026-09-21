<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import { MAX_VOICE_MS, useVoiceNote } from '@/composables/voiceNote'
import http, { type ApiResp } from '@/api/http'

const router = useRouter()

const text = ref('')
const mood = ref(0) // 用户自评心情 1-5（可跳过）
const submitting = ref(false)
const error = ref('')

const len = computed(() => text.value.trim().length)
const canSubmit = computed(() => len.value >= 10 && !submitting.value)

/* ---- C1 草稿：localStorage 即时 + 服务端 30s 自动保存，换端/崩溃不丢 ---- */
const DRAFT_LS = 'sv_diary_draft'
let draftSnapshot = ''
onMounted(async () => {
  const local = localStorage.getItem(DRAFT_LS)
  if (local?.trim()) {
    text.value = local
    return
  }
  try {
    const { data } = await http.get<ApiResp<{ content: string }>>('/diaries/draft')
    if (data.data.content) text.value = data.data.content
  } catch {
    /* 无草稿或离线 */
  }
})
watch(text, v => {
  if (v.trim()) localStorage.setItem(DRAFT_LS, v)
  else localStorage.removeItem(DRAFT_LS)
})
async function pushDraft() {
  if (!text.value.trim() || text.value === draftSnapshot) return
  try {
    await http.put('/diaries/draft', { content: text.value })
    draftSnapshot = text.value
  } catch {
    /* 静默：本地仍有兜底 */
  }
}
const draftTimer = window.setInterval(pushDraft, 30000)
onUnmounted(() => window.clearInterval(draftTimer))
async function clearDrafts() {
  localStorage.removeItem(DRAFT_LS)
  draftSnapshot = ''
  try {
    await http.delete('/diaries/draft')
  } catch {
    /* ignore */
  }
}

/* ---- M11 语音日记：录完先转成文字交回来校对，成稿仍走同一条 DIARY_PIPELINE ---- */
const voiceSource = ref<{ durationMs: number } | null>(null)
const {
  supported: voiceSupported,
  recording: voiceRecording,
  starting: voiceStarting,
  transcribing: voiceTranscribing,
  elapsedMs: voiceElapsed,
  error: voiceError,
  start: voiceStart,
  stop: voiceStop,
  cancel: voiceCancel,
} = useVoiceNote((transcript, durationMs) => {
  // 追加而非覆盖：用户可能已经手写了几句，转写不该把他的话吞掉
  text.value = text.value.trim() ? `${text.value.replace(/\s+$/, '')}\n${transcript}` : transcript
  voiceSource.value = { durationMs }
})
const voiceSeconds = computed(() => Math.round(voiceElapsed.value / 1000))

const MOODS = [
  { v: 1, e: '😖', t: '很低落' },
  { v: 2, e: '😞', t: '不太好' },
  { v: 3, e: '😐', t: '一般' },
  { v: 4, e: '🙂', t: '还不错' },
  { v: 5, e: '😄', t: '很好' },
]

/** 建任务后交棒给分析页（SSE 支持 Last-Event-ID 重放，刷新不丢进度） */
async function save() {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>('/tasks', {
      pipelineCode: 'DIARY_PIPELINE',
      payload: {
        diaryText: text.value,
        // 本地日期（en-CA 恒为 YYYY-MM-DD）：O1 业务时区语义，避免 UTC 跨日错标
        recordDate: new Date().toLocaleDateString('en-CA'),
        moodSelfRating: mood.value || undefined,
        // M11：语音只多带一个溯源标记与时长，正文与手写完全同路（后端对未知值一律按 TEXT 落）
        ...(voiceSource.value ? { diarySource: 'VOICE', voiceDurationMs: voiceSource.value.durationMs } : {}),
      },
      clientReqId: `web-${Date.now()}`,
    })
    await pushDraft()
    clearDrafts()
    text.value = ''
    mood.value = 0
    voiceSource.value = null
    router.push({ path: '/diaries/analysis', query: { task: data.data.taskNo } })
  } catch (e) {
    error.value = (e as Error).message || '提交失败'
    submitting.value = false
  }
}
</script>

<template>
  <div class="write">
    <SvNavBar title="情绪日记" back="返回" :large="false" />

    <div class="body">
      <textarea
        v-model="text"
        rows="9"
        maxlength="5000"
        aria-label="今天的心情记录"
        placeholder="今天发生了什么？你感觉怎么样？写下来，让心屿陪你梳理一下……"
      ></textarea>

      <div class="meta">
        <div class="moods" role="group" aria-label="今天心情 1 到 5 分">
          <button
            v-for="m in MOODS"
            :key="m.v"
            class="mood"
            :class="{ on: mood === m.v }"
            :aria-pressed="mood === m.v"
            :aria-label="m.t"
            @click="mood = mood === m.v ? 0 : m.v"
          >
            {{ m.e }}
          </button>
        </div>
        <span class="counter sv-cap" :class="{ short: len > 0 && len < 10 }">{{ len }}/5000</span>
      </div>

      <div v-if="voiceSupported" class="voice">
        <button
          v-if="!voiceRecording"
          class="mic"
          :disabled="voiceTranscribing || voiceStarting"
          :aria-busy="voiceStarting"
          @click="voiceStart"
        >
          <SvIcon name="i-mic" :size="19" />
          <span>{{ voiceStarting ? '正在开麦…' : voiceTranscribing ? '正在转写…' : '说给心屿听' }}</span>
        </button>
        <template v-else>
          <button class="mic on" :aria-label="`正在录音 ${voiceSeconds} 秒，点击结束`" @click="voiceStop">
            <span class="dot" aria-hidden="true"></span>
            <span>{{ voiceSeconds }}″ / {{ MAX_VOICE_MS / 1000 }}″</span>
          </button>
          <button class="mic ghost" @click="voiceCancel">不要这段</button>
        </template>
        <p class="sv-cap voice-hint" aria-live="polite">
          {{
            voiceRecording
              ? '正在录，说完点一下就停；一段最多 90 秒'
              : voiceStarting
                ? '正在开麦，浏览器问权限时点允许就行'
                : '说完转成文字，你先看一眼再交给心屿'
          }}
        </p>
      </div>

      <p v-if="voiceError" class="err" role="alert">{{ voiceError }}</p>
      <p v-else-if="voiceSource" class="sv-cap proofread" role="status">
        这段里有语音转写（{{ Math.round(voiceSource.durationMs / 1000) }}″），机器可能听错字——先校对一下再交。
      </p>

      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <button class="sv-btn" :disabled="!canSubmit" @click="save">
        {{ submitting ? '正在交给心屿…' : len < 10 ? '再多写一点吧（至少 10 字）' : '交给心屿梳理' }}
      </button>
      <p class="sv-cap hint">四个 Agent 会依次感知情绪、追溯来源、陪你疏导、归档结论，约需十几秒。</p>
    </div>
  </div>
</template>

<style scoped>
.body {
  padding: 0 var(--sv-s4);
}
textarea {
  width: 100%;
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  border-radius: var(--sv-r-card);
  padding: 14px 16px;
  font-size: var(--sv-fs-body);
  resize: vertical;
  font-family: inherit;
  color: var(--sv-label);
  outline: none;
  line-height: 1.7;
}
textarea:focus {
  border-color: var(--sv-indigo);
}
.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: var(--sv-s3) 0 var(--sv-s4);
  flex-wrap: wrap;
}
.moods {
  display: flex;
  gap: 6px;
}
.mood {
  border: 1.5px solid transparent;
  background: var(--sv-fill3);
  border-radius: var(--sv-r-ctl);
  font-size: 20px;
  min-width: 44px;
  min-height: 44px;
  cursor: pointer;
  opacity: 0.55;
  transition: 0.15s;
}
.mood.on {
  opacity: 1;
  border-color: var(--sv-indigo);
  background: var(--sv-indigo-soft);
}
.counter.short {
  color: var(--sv-amber);
}
.err {
  color: var(--sv-red);
  font-size: var(--sv-fs-footnote);
  margin-bottom: var(--sv-s2);
}
/* M11 语音日记：录音胶囊与校对提示（复用既有 token，不新增色值） */
.voice {
  margin-bottom: var(--sv-s3);
}
.mic {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 44px;
  padding: 0 16px;
  border-radius: 999px;
  border: 1.5px solid var(--sv-indigo);
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
  font-size: var(--sv-fs-footnote);
  font-weight: 600;
  cursor: pointer;
}
.mic:disabled {
  opacity: 0.55;
}
.mic.on {
  border-color: var(--sv-red);
  background: var(--sv-fill3);
  color: var(--sv-red);
  font-variant-numeric: tabular-nums;
}
.mic.ghost {
  border-color: transparent;
  background: var(--sv-fill3);
  color: var(--sv-label2);
  margin-left: 8px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--sv-red);
  animation: voice-pulse 1.1s ease-in-out infinite;
}
@keyframes voice-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
  }
}
.voice-hint {
  margin: 8px 0 0;
}
.proofread {
  display: block;
  margin-bottom: var(--sv-s3);
  color: var(--sv-amber);
}
.hint {
  text-align: center;
  margin-top: var(--sv-s2);
}
</style>
