<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { deleteDiary, diaryDetail, editDiary, reanalyzeDiary, type DiaryDetail as Diary } from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession } from '@/composables/session'
import { confirmSheet, toast } from '@/utils/feedback'
import { emotionOf } from '@/utils/emotions'

/** 详情页：正文只有这一页会解密读取，列表永远只见预览——少一处明文露面。 */
const { themeClass } = useTheme()
const id = ref(0)
const d = ref<Diary | null>(null)
const editing = ref(false)
const draft = ref('')
const busy = ref(false)

const moodWord = computed(() => (d.value?.moodSelfRating ? `${d.value.moodSelfRating} / 5` : '未评'))
const voiceSec = computed(() =>
  d.value && d.value.source === 'VOICE' && d.value.voiceDurationMs
    ? `${Math.round(d.value.voiceDurationMs / 1000)} 秒录音转写`
    : '',
)

onLoad(q => {
  applyTheme()
  if (!ensureSession()) return
  id.value = Number((q as Record<string, string>).id || 0)
  void load()
})

async function load() {
  try {
    d.value = await diaryDetail(id.value)
    draft.value = d.value.content
  } catch (e) {
    toast((e as Error).message)
  }
}

async function save() {
  if (busy.value) return
  busy.value = true
  try {
    await editDiary(id.value, draft.value.trim())
    editing.value = false
    toast('改好了；下面的梳理结果会标为待更新')
    await load()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function reanalyze() {
  if (busy.value) return
  busy.value = true
  try {
    await reanalyzeDiary(id.value)
    toast('已重新排队梳理，稍后回来看不一样的地方')
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function remove() {
  const ok = await confirmSheet('删除这篇', '删除后这篇日记及它的梳理结果都会消失，且无法恢复。')
  if (!ok) return
  try {
    await deleteDiary(id.value)
    toast('已删除')
    uni.navigateBack()
  } catch (e) {
    toast((e as Error).message)
  }
}
</script>

<template>
  <view class="sv-page detail" :class="themeClass">
    <view v-if="!d" class="sv-surface"><text class="sv-muted">正在打开这一页…</text></view>

    <template v-else>
      <view class="head">
        <view class="head-text">
          <text class="sv-h1">{{ d.recordDate }}</text>
          <text class="sv-muted">自评心情 {{ moodWord }}{{ voiceSec ? ' · ' + voiceSec : '' }}</text>
        </view>
        <text class="link" @tap="editing = !editing">{{ editing ? '取消' : '编辑' }}</text>
      </view>

      <textarea
        v-if="editing"
        v-model="draft"
        class="sv-field paper"
        :maxlength="5000"
        placeholder-class="ph"
      />
      <view v-else class="sv-surface body">
        <text class="body-text">{{ d.content }}</text>
      </view>
      <view v-if="editing" class="edit-actions">
        <button class="sv-btn" :class="{ 'is-disabled': busy }" @tap="save">保存修改</button>
      </view>

      <view v-if="d.emotions.length" class="sv-surface block">
        <text class="sv-h2">这一天被记到的情绪</text>
        <view class="chips">
          <view v-for="(em, i) in d.emotions" :key="i" class="chip">
            <view class="chip-dot" :style="{ background: 'var(' + emotionOf(em.emotion).varName + ')' }" />
            <text class="chip-text">{{ em.emotion }}</text>
            <text class="sv-cap">{{ em.sourceType === 'SELF_RATING' ? '自己说的' : '梳理出的' }}</text>
          </view>
        </view>
      </view>

      <view v-if="d.reports.length" class="sv-surface block">
        <text class="sv-h2">梳理结果</text>
        <view v-for="r in d.reports" :key="r.id" class="report">
          <text class="report-title">{{ r.title }}</text>
          <text class="sv-cap">
            {{ r.type }} · 风险级别 {{ r.riskLevel || '无' }}{{ r.stale ? ' · 内容改过了，待更新' : '' }}
          </text>
        </view>
        <text class="sv-cap hint">完整报告与图表在网页端；这里只留"这一篇得出了什么"。</text>
      </view>

      <view class="danger-zone">
        <button class="sv-btn plain" :class="{ 'is-disabled': busy }" @tap="reanalyze">再梳理一次</button>
        <button class="sv-btn plain danger-text" @tap="remove">删除这一篇</button>
      </view>
    </template>
    <view class="tab-spacer" />
  </view>
</template>

<style scoped>
.detail {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.head {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
}
.head-text {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.sv-h1 {
  font-size: var(--sv-fs-title2);
  margin-bottom: 0;
}
.link {
  color: var(--sv-indigo);
  font-size: var(--sv-fs-subhead);
}
.body {
  padding: var(--sv-s4);
}
.body-text {
  font-size: var(--sv-fs-body);
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
.paper {
  min-height: 260px;
  line-height: 1.8;
}
.ph {
  color: var(--sv-label3);
}
.edit-actions {
  display: flex;
  flex-direction: row;
}
.block {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.chips {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: var(--sv-s2);
}
.chip {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--sv-s1);
  padding: 4px 10px;
  border-radius: var(--sv-r-pill);
  background: var(--sv-fill3);
}
.chip-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.chip-text {
  font-size: var(--sv-fs-footnote);
}
.report {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-top: var(--sv-s2);
  border-top: 1px solid var(--sv-sep);
}
.report-title {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
}
.hint {
  line-height: 1.5;
}
.danger-zone {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.danger-zone .sv-btn {
  flex: 1;
}
.danger-text {
  color: var(--sv-red);
}
.tab-spacer {
  height: var(--sv-s4);
}
</style>
