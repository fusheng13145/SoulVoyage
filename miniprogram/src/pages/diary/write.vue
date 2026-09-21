<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { onLoad, onHide } from '@dcloudio/uni-app'
import { diaryDraft, diaryList, dropDraft, saveDraft, submitDiary, taskOf } from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go } from '@/composables/session'
import { toast } from '@/utils/feedback'
import { reqId, todayStr } from '@/utils/date'

/** 写作页：草稿是"没写完也别丢"，成稿才交 DIARY_PIPELINE。
 *  两步分开是端侧口径：本地存稿不产生任何模型调用，也不进任何分析。 */
const { themeClass } = useTheme()
const content = ref('')
const mood = ref(0)
const submitting = ref(false)
const stage = ref('')
let poll: ReturnType<typeof setTimeout> | null = null

const left = computed(() => 5000 - content.value.length)
const canSubmit = computed(() => content.value.trim().length >= 10 && !submitting.value)

onLoad(async () => {
  if (!ensureSession()) return
  applyTheme()
  try {
    const d = await diaryDraft()
    if (d?.content) {
      content.value = d.content
      toast('上次没写完的，已经替你留着')
    }
  } catch {
    /* 草稿读不到不挡写作：大不了这一篇从零开始写 */
  }
})

onUnmounted(() => {
  if (poll) clearTimeout(poll)
})

/* 离页即存：草稿的意义就是"没写完也不会丢"，不逼人记得按按钮 */
onHide(() => {
  if (content.value.trim() && !submitting.value) void saveDraft(content.value).catch(() => {})
})

async function keepDraft() {
  try {
    if (content.value.trim()) await saveDraft(content.value)
    else await dropDraft()
    toast('存好了，随时回来接着写')
  } catch (e) {
    toast((e as Error).message)
  }
}

/** 成稿：建档只拿到任务号，剩下的等管道——这里把等待显式画出来，而不是让人对着转圈猜 */
async function submit() {
  if (!canSubmit.value) {
    toast(content.value.trim().length < 10 ? '再多写几个字吧，至少让我看清一点' : '正在梳理中，稍等一下')
    return
  }
  submitting.value = true
  stage.value = '交出去了…'
  try {
    const r = await submitDiary({
      diaryText: content.value.trim(),
      recordDate: todayStr(),
      moodSelfRating: mood.value || undefined,
      clientReqId: reqId(),
    })
    await dropDraft() // 成稿即清草稿：留着会让下一次写作以为"上次没写完"
    await waitTask(r.taskNo)
  } catch (e) {
    stage.value = ''
    submitting.value = false
    toast((e as Error).message)
  }
}

/** 轮询到不再是 PENDING/RUNNING 为止；16 次 × 1.5s 约 24 秒还没完就让人先离开，回列表一样看得到 */
async function waitTask(taskNo: string) {
  for (let i = 0; i < 16; i++) {
    await new Promise(resolve => (poll = setTimeout(resolve, 1500)))
    const t = await taskOf(taskNo).catch(() => null)
    if (!t) continue
    stage.value = t.status === 'RUNNING' ? '正在梳理…' : t.status === 'WAITING_USER' ? '等你确认' : ''
    if (!['PENDING', 'RUNNING'].includes(t.status)) {
      submitting.value = false
      if (t.status === 'FAILED') {
        toast(t.errorMsg || '这一次没梳理完，原文还在，稍后再试')
        return
      }
      await openNewest()
      return
    }
  }
  submitting.value = false
  stage.value = ''
  toast('梳理还在后台跑，稍后回日记本看结果')
}

/** 建档回执只有任务号，日记本体由管道写库——所以按记录日期把最新一条找回来 */
async function openNewest() {
  const day = todayStr()
  const r = await diaryList({ from: day, to: day, page: 0, size: 1 }).catch(() => null)
  const id = r?.items[0]?.id
  if (id) uni.redirectTo({ url: `/pages/diary/detail?id=${id}` })
  else {
    toast('已经收好了，回日记本就能看到')
    go('/pages/diary/index')
  }
}
</script>

<template>
  <view class="sv-page write" :class="themeClass">
    <view class="head">
      <text class="sv-h1">今天想记点什么</text>
      <text class="sv-muted">写完再交给心屿梳理；只存草稿的话，一个字都不会进模型。</text>
    </view>

    <textarea
      v-model="content"
      class="sv-field paper"
      :maxlength="5000"
      placeholder="想到什么写什么，不用通顺。"
      placeholder-class="ph"
    />
    <text class="sv-cap counter">{{ left }} 字可写</text>

    <view class="mood">
      <text class="sv-cap">今天的自评心情</text>
      <view class="dots">
        <view
          v-for="n in 5"
          :key="n"
          class="dot"
          :class="{ on: mood >= n }"
          @tap="mood = mood === n ? 0 : n"
        />
      </view>
      <text class="sv-cap">{{ mood ? `1 到 5 里的 ${mood}` : '可以不选' }}</text>
    </view>

    <view class="actions">
      <button class="sv-btn ghost" @tap="keepDraft">存草稿</button>
      <button class="sv-btn" :class="{ 'is-disabled': !canSubmit }" @tap="submit">
        {{ stage || '交给心屿梳理' }}
      </button>
    </view>
    <text class="sv-cap tip">语音日记、编辑与重分析在网页端更顺手；这一版先把"写下来"做扎实。</text>
  </view>
</template>

<style scoped>
.write {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.head {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.sv-h1 {
  margin-bottom: 0;
}
.paper {
  min-height: 300px;
  line-height: 1.7;
}
.ph {
  color: var(--sv-label3);
}
.counter {
  text-align: right;
}
.mood {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--sv-s3);
}
.dots {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--sv-fill2);
}
.dot.on {
  background: var(--sv-amber);
}
.actions {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.actions .sv-btn {
  flex: 1;
}
.tip {
  line-height: 1.5;
}
</style>
