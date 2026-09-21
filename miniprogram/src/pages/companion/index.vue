<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  companionActive,
  companionEnd,
  companionNoAnalyze,
  companionOpen,
  companionTranscript,
  companionTurn,
  type TurnView,
} from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go } from '@/composables/session'
import { toast } from '@/utils/feedback'

/** 树洞（下篇·C0）：一段一段地聊，不建长期会话号——收段后交给 COMPANION_PIPELINE 消化。
 *
 *  两条红线在这里是页面上的可见事实，不是后端口头承诺：
 *  ① 危机一票拦截照旧——回合响应里带 crisis 事件时立刻跳资源页，端侧不做"要不要提示"的判断；
 *  ② 漫聊原文照常加密落库，本页面不回显任何"分析结论"，只有用户自己说过的话和 AI 的回复。 */
const { themeClass } = useTheme()

const sessionId = ref(0)
const segmentNo = ref(1)
const turns = ref<TurnView[]>([])
const text = ref('')
const sending = ref(false)
const ending = ref(false)
const remaining = ref<number | null>(null)
const scrollInto = ref('')
const loaded = ref(false)

const canSend = computed(() => !!text.value.trim() && !sending.value)
const quota = computed(() =>
  remaining.value === null ? '' : remaining.value <= 0 ? '今天到这里了' : `今天还能聊 ${remaining.value} 轮`,
)

onShow(() => {
  applyTheme()
  if (ensureSession()) void boot()
})

/** 有活跃段就续上，没有就先不建（点开"说说话"才建段，避免进一次页面就多一段空会话） */
async function boot() {
  const s = await companionActive().catch(() => null)
  if (s) await openSession(s.sessionId)
  loaded.value = true
}

async function openSession(id: number) {
  if (!id) {
    toast('这一段没开起来，再点一次就好')
    return
  }
  sessionId.value = id
  const t = await companionTranscript(id).catch(() => null)
  if (!t) {
    toast('这一段打不开了，开一段新的吧')
    turns.value = []
    return
  }
  segmentNo.value = t.segmentNo
  turns.value = t.turnList ?? []
  await scrollToLast()
}

async function startNew() {
  try {
    const s = await companionOpen()
    await openSession(s.sessionId)
  } catch (e) {
    toast((e as Error).message)
  }
}

async function send() {
  if (!canSend.value) return
  if (!sessionId.value) await startNew()
  if (!sessionId.value) {
    toast('这一段还没开起来，稍等一下再发')
    return
  }
  const saying = text.value.trim()
  text.value = ''
  sending.value = true
  try {
    const { done, crisis } = await companionTurn(sessionId.value, saying)
    turns.value = [
      ...turns.value,
      {
        turnId: done.turnId,
        turnNo: done.turnNo,
        userText: saying,
        aiText: done.aiText,
        moodTag: done.moodTag,
        crisis: done.crisis,
        noAnalyze: false,
      },
    ]
    remaining.value = done.remainingToday
    await scrollToLast()
    // 危机事件不由用户点，也不给"以后再说"：直接把能打通的电话放到眼前
    if (crisis) go('/pages/resources/index')
  } catch (e) {
    text.value = saying // 话没送出去就还给人家，别让人重新打一遍
    toast((e as Error).message)
  } finally {
    sending.value = false
  }
}

async function skipThis(turnId: number) {
  try {
    await companionNoAnalyze(turnId)
    turns.value = turns.value.map(t => (t.turnId === turnId ? { ...t, noAnalyze: true } : t))
    toast('这一段不会进梳理')
  } catch (e) {
    toast((e as Error).message)
  }
}

async function endSegment() {
  if (!sessionId.value || ending.value) return
  ending.value = true
  try {
    const r = await companionEnd(sessionId.value)
    toast(r.taskNo ? '这一段收好了，梳理结果稍后见' : '这一段没有需要梳理的内容')
    sessionId.value = 0
    turns.value = []
    remaining.value = null
  } catch (e) {
    toast((e as Error).message)
  } finally {
    ending.value = false
  }
}

async function scrollToLast() {
  await nextTick()
  const last = turns.value[turns.value.length - 1]
  scrollInto.value = last ? `t${last.turnNo}` : ''
}
</script>

<template>
  <view class="sv-page chat" :class="themeClass">
    <view class="head">
      <view class="head-text">
        <text class="sv-h1">树洞</text>
        <text class="sv-muted">
          {{ sessionId ? `第 ${segmentNo} 段 · ${quota}` : '说说话，不用组织语言，也不用顾前后' }}
        </text>
      </view>
      <text v-if="sessionId" class="link" @tap="endSegment">{{ ending ? '收着…' : '收段' }}</text>
    </view>

    <scroll-view class="log" scroll-y :scroll-into-view="scrollInto">
      <view v-if="!loaded" class="empty">
        <text class="sv-muted">正在看上次聊到哪儿…</text>
      </view>
      <view v-else-if="!turns.length" class="empty">
        <text class="sv-h2">这里没有别人</text>
        <text class="sv-muted"
          >写下第一句就好。想到哪儿说到哪儿，随时可以叫停，也可以一句一句被允许不说。</text
        >
        <button v-if="!sessionId" class="sv-btn" @tap="startNew">开始这一段</button>
      </view>

      <view v-for="t in turns" :id="`t${t.turnNo}`" :key="t.turnNo" class="turn">
        <view class="bubble user">
          <text class="bubble-text">{{ t.userText }}</text>
        </view>
        <view class="user-meta">
          <text v-if="t.noAnalyze" class="sv-cap">已跳过梳理</text>
          <text v-else class="skip" @tap="skipThis(t.turnId)">这句别分析</text>
        </view>
        <view class="bubble ai" :class="{ crisis: t.crisis }">
          <text class="bubble-text">{{ t.aiText }}</text>
        </view>
        <text v-if="t.moodTag" class="sv-cap mood">听上去像：{{ t.moodTag }}</text>
      </view>
      <view v-if="sending" class="bubble ai pending">
        <text class="bubble-text">在想怎么说…</text>
      </view>
      <view class="log-tail" />
    </scroll-view>

    <view class="composer">
      <textarea
        v-model="text"
        class="input"
        :maxlength="500"
        :auto-height="true"
        placeholder="说点什么…"
        placeholder-class="ph"
        confirm-type="send"
        :disabled="sending"
      />
      <button class="sv-btn send" :class="{ 'is-disabled': !canSend }" @tap="send">
        {{ sending ? '想着' : '发送' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 0;
  overflow: hidden;
}
.head {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  padding: calc(var(--sv-s4) + var(--sv-safe-t)) var(--sv-s4) var(--sv-s3);
}
.head-text {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.sv-h1 {
  margin-bottom: 0;
}
.link {
  color: var(--sv-indigo);
  font-size: var(--sv-fs-subhead);
}
.log {
  flex: 1;
  padding: 0 var(--sv-s4);
}
.empty {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
  padding-top: var(--sv-s7);
}
.turn {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
  margin-bottom: var(--sv-s4);
}
.bubble {
  max-width: 84%;
  padding: var(--sv-s3) var(--sv-s4);
  border-radius: var(--sv-r-card);
}
.bubble.user {
  align-self: flex-end;
  background: var(--sv-indigo);
  color: #fff;
}
.bubble.ai {
  align-self: flex-start;
  background: var(--sv-card);
  color: var(--sv-label);
}
.sv-dark .bubble.ai {
  border: 1px solid var(--sv-sep);
}
.bubble.ai.crisis {
  border: 1px solid var(--sv-red);
}
.bubble.pending {
  color: var(--sv-label2);
}
.bubble-text {
  font-size: var(--sv-fs-body);
  line-height: 1.7;
  word-break: break-word;
}
.user-meta {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  min-height: 20px;
}
.skip {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
  padding: 0 var(--sv-s1);
}
.mood {
  color: var(--sv-label3);
}
.log-tail {
  height: var(--sv-s4);
}
.composer {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  gap: var(--sv-s2);
  padding: var(--sv-s3) var(--sv-s4) calc(var(--sv-s3) + env(safe-area-inset-bottom, 0px));
  background: var(--sv-card);
  border-top: 1px solid var(--sv-sep);
}
.input {
  flex: 1;
  max-height: 120px;
  min-height: 40px;
  padding: 8px var(--sv-s3);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  background: var(--sv-bg);
  color: var(--sv-label);
  font-size: var(--sv-fs-body);
  line-height: 1.6;
}
.ph {
  color: var(--sv-label3);
}
.send {
  width: 84px;
  min-height: 40px;
  padding: 8px 0;
}
</style>
