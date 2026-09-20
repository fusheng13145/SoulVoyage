<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvBubble from '@/components/ui/SvBubble.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import http, { type ApiResp } from '@/api/http'

/** 漫聊会话视图（后端 SessionView 的前端收窄） */
interface CompanionSession {
  sessionId: number
  segmentNo: number
  status: string
  turnList?: {
    turnId: number
    turnNo: number
    userText: string
    aiText: string
    crisis?: boolean
    noAnalyze?: boolean
  }[]
}
import { postSse } from '@/api/sse'
import { toast } from '@/stores/ui'
import { useCrisisStore } from '@/stores/crisis'

interface Turn {
  turnId?: number
  turnNo?: number
  text: string
  ai?: boolean
  crisis?: boolean
  noAnalyze?: boolean
}

const crisis = useCrisisStore()

const sessionId = ref<number | null>(null)
const segmentNo = ref(1)
const msgs = ref<Turn[]>([])
const draft = ref('')
const streaming = ref(false)
const sealed = ref(false)
const ended = ref(false)
const chatBox = ref<HTMLElement | null>(null)
const remaining = ref<number | null>(null)
const loading = ref(true)

const moodHint = computed(() =>
  remaining.value !== null && remaining.value <= 20 ? `今天还能聊 ${remaining.value} 句（明天我还在）` : '',
)

async function open() {
  loading.value = true
  try {
    const { data } = await http.post<ApiResp<CompanionSession>>('/companion/sessions', {})
    sessionId.value = data.data.sessionId
    segmentNo.value = data.data.segmentNo
    ended.value = data.data.status !== 'ACTIVE'
    msgs.value = []
    for (const t of data.data.turnList || []) {
      msgs.value.push({ turnId: t.turnId, turnNo: t.turnNo, text: t.userText, noAnalyze: t.noAnalyze })
      msgs.value.push({
        turnId: t.turnId,
        turnNo: t.turnNo,
        text: t.aiText,
        ai: true,
        crisis: t.crisis,
        noAnalyze: t.noAnalyze,
      })
    }
    if (!msgs.value.length) {
      msgs.value.push({ text: GREET, ai: true })
    }
    if (ended.value)
      msgs.value.push({
        text: '这一段已经收进行囊了。想继续聊，回到今日页点「来漫聊」开一段新的。',
        ai: true,
      })
  } catch (e) {
    toast((e as Error).message || '树洞暂时没打开')
  } finally {
    loading.value = false
    scroll()
  }
}
const GREET =
  '我在呢。这里没有打分也没有分析压力——想说什么就说，写完想撤也没关系（每条回复你都可以标「这句别分析」）。'
onMounted(open)

function scroll() {
  nextTick(() => chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight }))
}

async function send() {
  const t = draft.value.trim()
  if (!t || streaming.value || sealed.value || ended.value || !sessionId.value) return
  draft.value = ''
  msgs.value.push({ text: t })
  msgs.value.push({ text: '', ai: true })
  const idx = msgs.value.length - 1
  streaming.value = true
  scroll()
  try {
    await postSse(`/companion/sessions/${sessionId.value}/turns`, { userText: t }, e => {
      const ai = msgs.value[idx]
      if (e.event === 'ai_delta') {
        ai.text += e.data.text
        scroll()
      } else if (e.event === 'turn_done') {
        ai.turnId = e.data.turnId
        ai.turnNo = e.data.turnNo
        ai.crisis = !!e.data.crisis
        remaining.value = e.data.remainingToday
        if (!ai.text) ai.text = '……'
      } else if (e.event === 'crisis') {
        ai.crisis = true
      } else if (e.event === 'error') {
        msgs.value.splice(idx, 1)
        toast(e.data.msg || '这轮没接上，再说一次试试')
      }
      scroll()
    })
  } catch {
    toast('连接中断，你的话没有丢，刷新后还在')
  } finally {
    streaming.value = false
  }
}

async function hideFromAnalysis(turn: Turn) {
  if (!turn.turnId || turn.noAnalyze) return
  try {
    await http.post(`/companion/turns/${turn.turnId}/no-analyze`, {})
    turn.noAnalyze = true
    toast('好，这句只留在当下，不进分析')
  } catch (e) {
    toast((e as Error).message || '操作失败')
  }
}

async function endChat() {
  if (!sessionId.value || sealed.value) return
  sealed.value = true
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>(
      `/companion/sessions/${sessionId.value}/end`,
      {},
    )
    msgs.value.push({
      text: data.data.taskNo ? '收好啦。这段时间我会轻轻归档进你的情绪地图。' : '收好啦。想继续随时回来。',
      ai: true,
    })
  } catch {
    sealed.value = false
    toast('刚才没收到，再点一次试试')
  }
  scroll()
}
</script>

<template>
  <div class="cpn">
    <SvNavBar
      title="漫聊"
      :subtitle="`树洞 · 第 ${segmentNo} 段`"
      back="今日"
      back-to="/today"
      :large="false"
    >
      <template #actions>
        <button
          v-if="!ended && sessionId"
          class="end-btn"
          :disabled="sealed"
          aria-label="收起这段对话"
          @click="endChat"
        >
          <SvIcon name="i-check" :size="20" tone="inherit" />
        </button>
      </template>
    </SvNavBar>

    <div class="body">
      <p v-if="loading" class="sv-muted wait">树洞正在打开…</p>

      <SvCard v-if="crisis.crisisMode" :pad="false" class="care">
        <CrisisReferral level="MEDIUM" headline="这段时间漫聊会一直陪着你——不用组织语言，说碎片也可以" />
      </SvCard>

      <div ref="chatBox" class="chat sv-scroll" aria-live="polite">
        <template v-for="(m, i) in msgs" :key="i">
          <SvBubble :role="m.ai === undefined ? 'me' : m.ai ? 'ai' : 'me'" :name="m.ai ? '心屿' : undefined">
            {{ m.text }}
            <template v-if="m.ai && m.turnNo && sessionId && !ended">
              <button v-if="!m.noAnalyze" class="hide" @click="hideFromAnalysis(m)">这句别分析</button>
              <span v-else class="hide done">已排除分析 ✓</span>
            </template>
            <small v-if="m.noAnalyze && !m.ai" class="sv-cap">（这句已标记不进分析）</small>
          </SvBubble>
          <CrisisReferral
            v-if="m.crisis"
            level="HIGH"
            headline="我察觉到你此刻可能很难受——你不需要一个人扛，这些资源一直在"
          />
        </template>
        <SvBubble v-if="streaming && !msgs[msgs.length - 1]?.text" role="typing" />
      </div>

      <p v-if="moodHint" class="sv-cap hint" role="status">{{ moodHint }}</p>
      <SvDisclaimer
        text="漫聊是低压力陪伴空间：回复由 AI 生成，不替代专业咨询；危机信号会当轮触发求助资源。"
        reason="你可以随时点「这句别分析」让某句话留在当下——收段后的情绪消化，永远听你的。"
      />

      <div v-if="!ended" class="input-row">
        <textarea
          v-model="draft"
          rows="2"
          :disabled="streaming || sealed"
          aria-label="想说什么"
          placeholder="说点什么…不想写全也没关系"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button class="send" :disabled="streaming || sealed || !draft.trim()" aria-label="发送" @click="send">
          <SvIcon name="i-send" :size="20" tone="inherit" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.body {
  padding: 0 var(--sv-s4);
  display: flex;
  flex-direction: column;
}
.wait {
  text-align: center;
  padding: var(--sv-s5) 0;
}
.care {
  margin-bottom: var(--sv-s3);
}
.chat {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--sv-bg);
  border-radius: var(--sv-r-card);
  padding: var(--sv-s3);
  box-shadow: inset 0 0 0 1px var(--sv-sep);
  min-height: 40vh;
  max-height: 52vh;
  overflow-y: auto;
  margin-bottom: var(--sv-s3);
}
.hide {
  margin-left: 10px;
  border: none;
  background: transparent;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
  cursor: pointer;
  text-decoration: underline;
  padding: 0;
  font-family: inherit;
}
.hide.done {
  cursor: default;
  text-decoration: none;
  color: var(--sv-mint);
}
.hint {
  text-align: center;
  margin-bottom: var(--sv-s2);
}
.end-btn {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: none;
  background: transparent;
  color: var(--sv-indigo);
  cursor: pointer;
}
.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
textarea {
  flex: 1;
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  border-radius: var(--sv-r-card);
  padding: 12px 14px;
  font-size: var(--sv-fs-subhead);
  font-family: inherit;
  resize: none;
  color: var(--sv-label);
  outline: none;
}
textarea:focus {
  border-color: var(--sv-indigo);
}
.send {
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: grid;
  place-items: center;
  background: var(--sv-indigo);
  color: #fff;
  transition: transform 0.12s var(--sv-ease);
}
.send:active {
  transform: scale(0.92);
}
.send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
