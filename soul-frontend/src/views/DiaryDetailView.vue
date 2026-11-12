<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvSkeleton from '@/components/ui/SvSkeleton.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import { diaryApi } from '@/api/domains/diary'
import { taskApi } from '@/api/domains/task'
import { confirmDialog, toast } from '@/stores/ui'
import { EMOTION_BY_LABEL } from '@/utils/emotions'

interface ReportMeta {
  id: number
  type: string
  title: string
  riskLevel: string
  stale: number
}
interface EmotionPoint {
  sourceType: string
  emotion: string
  valence: number
  intensity: number
  eventTags: { tag: string; evidence?: string }[]
}
interface DiaryDetail {
  id: number
  recordDate: string
  moodSelfRating: number
  content: string
  taskId: number
  source: string
  voiceDurationMs: number
  reports: ReportMeta[]
  emotions: EmotionPoint[]
}

const route = useRoute()
const router = useRouter()

const detail = ref<DiaryDetail | null>(null)
const loading = ref(true)
const editing = ref(false)
function toggleEdit() {
  editing.value = !editing.value
  editText.value = detail.value?.content ?? ''
}
const editText = ref('')
const busy = ref('') // '' | 'saving' | 'reanalyzing'
let pollTimer: number | undefined

const MOOD_TONE: Record<number, string> = {
  1: 'var(--sv-danger)',
  2: 'var(--sv-warn)',
  3: 'var(--sv-label3)',
  4: 'color-mix(in srgb, var(--sv-ok) 60%, var(--sv-label3))',
  5: 'var(--sv-ok)',
}
const REPORT_TITLES: Record<string, string> = {
  DIARY_TRACE: '溯源复盘',
  DIARY_REPORT: '复盘报告',
  SIMULATE_REVIEW: '模拟复盘',
  WEEKLY: '周报',
}

const srcLabel = (s: string) => (s === 'SIMULATION' ? '模拟' : s === 'SELF_RATING' ? '打卡' : '日记')
const emoMeta = (label: string) => EMOTION_BY_LABEL[label]

async function open() {
  loading.value = true
  try {
    detail.value = await diaryApi.detail<DiaryDetail>(String(route.params.id))
  } catch (e) {
    toast((e as Error).message || '打开失败')
  } finally {
    loading.value = false
  }
}
onMounted(open)
onUnmounted(stopPoll)

async function saveEdit() {
  const d = detail.value
  if (!d || !editText.value.trim()) return
  busy.value = 'saving'
  try {
    await diaryApi.update(d.id, { content: editText.value, moodSelfRating: d.moodSelfRating })
    await open()
    editing.value = false
    toast('已保存，旧报告已标记待更新')
  } catch (e) {
    toast((e as Error).message || '保存失败')
  } finally {
    busy.value = ''
  }
}

async function remove() {
  const d = detail.value
  if (!d) return
  const ok = await confirmDialog({
    title: '删除这篇日记？',
    danger: true,
    confirmText: '删除',
    message: '删除后不再出现在列表中，相关复盘报告会标记过期。',
  })
  if (!ok) return
  try {
    await diaryApi.remove(d.id)
    toast('已删除')
    router.replace('/diaries')
  } catch (e) {
    toast((e as Error).message || '删除失败')
  }
}

/** 重新分析：按当前（可能已编辑的）内容再跑一轮 DIARY_PIPELINE，轮询到结束 */
async function reanalyze() {
  const d = detail.value
  if (!d || busy.value) return
  busy.value = 'reanalyzing'
  try {
    const { taskNo } = await diaryApi.reanalyze(d.id)
    pollTask(taskNo)
  } catch (e) {
    toast((e as Error).message || '提交重新分析失败')
    busy.value = ''
  }
}

function pollTask(taskNo: string) {
  const tick = async () => {
    try {
      const { status: st } = await taskApi.get<{ status: string }>(taskNo)
      if (st === 'SUCCESS' || st === 'PARTIAL_SUCCESS') {
        stopPoll()
        busy.value = ''
        await open()
        toast('重新分析完成')
        return
      }
      if (st === 'FAILED' || st === 'CANCELED') {
        stopPoll()
        busy.value = ''
        toast('重新分析未完成，稍后再试一次')
        return
      }
      pollTimer = window.setTimeout(tick, 2000)
    } catch {
      pollTimer = window.setTimeout(tick, 4000) // 瞬时网络抖动不打断轮询
    }
  }
  pollTimer = window.setTimeout(tick, 2000)
}
function stopPoll() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

const riskTone = (r: string) => ({ HIGH: 'var(--sv-danger)', MEDIUM: 'var(--sv-warn)' })[r] || 'var(--sv-ok)'
</script>

<template>
  <div class="detail">
    <SvNavBar title="日记详情" :back="` ${detail?.recordDate || '返回'}`" back-to="/diaries" :large="false">
      <template #actions>
        <button class="ic" :disabled="!!busy" aria-label="删除这篇日记" @click="remove">
          <SvIcon name="i-trash" :size="19" />
        </button>
      </template>
    </SvNavBar>

    <SvPageBody v-if="detail">
      <SvCard>
        <div class="head">
          <time>{{ detail.recordDate }}</time>
          <span v-if="detail.moodSelfRating" class="mood">
            <i
              class="mood-dot"
              :style="{ background: MOOD_TONE[detail.moodSelfRating] }"
              aria-hidden="true"
            />{{ detail.moodSelfRating }}/5
          </span>
          <span
            v-if="detail.source === 'VOICE'"
            class="src"
            title="语音转写后校对成稿"
            aria-label="这是一篇语音转写的日记"
          >
            <SvIcon name="i-mic" :size="14" tone="label2" />
            <span v-if="detail.voiceDurationMs" class="src-dur"
              >{{ Math.round(detail.voiceDurationMs / 1000) }}″</span
            >
          </span>
          <span class="ops">
            <button class="op" :disabled="!!busy" @click="toggleEdit">
              {{ editing ? '取消' : '编辑' }}
            </button>
            <button v-if="!editing" class="op" :disabled="!!busy" @click="reanalyze">
              {{ busy === 'reanalyzing' ? '分析中…' : '重新分析' }}
            </button>
          </span>
        </div>
        <p v-if="busy === 'reanalyzing'" class="busy sv-muted" role="status">心屿正在重新梳理，约需十几秒…</p>

        <template v-if="editing">
          <textarea v-model="editText" rows="8" maxlength="5000" aria-label="编辑日记内容"></textarea>
          <div class="edit-row">
            <span class="sv-cap">保存后旧报告会标记为「待更新」</span>
            <button class="sv-btn sm" :disabled="!editText.trim() || !!busy" @click="saveEdit">保存</button>
          </div>
        </template>
        <p v-else class="content">{{ detail.content }}</p>
      </SvCard>

      <SvList v-if="detail.emotions.length" title="当日情绪">
        <SvCell
          v-for="(p, i) in detail.emotions"
          :key="i"
          :chevron="false"
          :label="p.emotion"
          :hint="`${srcLabel(p.sourceType)} · 强度 ${Math.round(p.intensity * 100)}% · 效价 ${p.valence}`"
        >
          <template #icon>
            <span class="dot" :style="{ background: `var(${emoMeta(p.emotion)?.varName || '--e-numb'})` }" />
          </template>
          <template v-if="p.eventTags?.length" #extra>
            <span class="tags"
              ><span v-for="t in p.eventTags" :key="t.tag" class="tag">{{ t.tag }}</span></span
            >
          </template>
        </SvCell>
      </SvList>

      <SvList v-if="detail.reports.length" title="关联复盘报告">
        <SvCell
          v-for="r in detail.reports"
          :key="r.id"
          :label="REPORT_TITLES[r.type] || r.title || r.type"
          :hint="r.stale ? '内容已更新，结论待重跑' : undefined"
          :to="r.stale ? undefined : `/archive/report/${r.id}`"
        >
          <template #icon>
            <span class="dot" :style="{ background: riskTone(r.riskLevel) }" />
          </template>
        </SvCell>
      </SvList>

      <p v-if="!detail.emotions.length && !detail.reports.length && !editing" class="sv-muted none">
        这篇还没有分析结果，点「重新分析」让心屿跑一次。
      </p>

      <SvDisclaimer text="日记以信封加密存储，删除为软删除；导出数据同样仅你可见" />
    </SvPageBody>

    <SvPageBody v-else-if="!loading"><p class="sv-muted">日记不存在或已删除。</p></SvPageBody>
    <SvPageBody v-else><SvSkeleton :h="120" /><SvSkeleton :h="80" w="70%" /></SvPageBody>
  </div>
</template>

<style scoped>
.ic {
  border: none;
  background: transparent;
  color: var(--sv-danger);
  cursor: pointer;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: var(--sv-s3);
}
.head time {
  color: var(--sv-label3);
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-footnote);
  font-variant-numeric: tabular-nums;
}
.mood {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
  font-variant-numeric: tabular-nums;
}
.mood-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.src {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--sv-label3);
  font-size: var(--sv-fs-footnote);
  font-variant-numeric: tabular-nums;
}
.ops {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.op {
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  border-radius: var(--sv-r-ctl);
  padding: 4px 10px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  min-height: 32px;
  font-family: inherit;
}
.op:disabled {
  opacity: 0.45;
}
.busy {
  margin-bottom: var(--sv-s2);
}
.content {
  white-space: pre-wrap;
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-prose);
  line-height: var(--sv-lh-prose);
}
textarea {
  width: 100%;
  border: 1px solid var(--sv-line-strong);
  background: var(--sv-card2);
  border-radius: var(--sv-r-ctl);
  padding: 12px;
  font-size: var(--sv-fs-callout);
  line-height: var(--sv-lh-prose);
  font-family: var(--sv-serif);
  resize: vertical;
  color: var(--sv-label);
  outline: none;
}
.edit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--sv-s2);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.tags {
  display: inline-flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.tag {
  font-size: var(--sv-fs-caption1);
  border: 1px solid var(--sv-sep);
  color: var(--sv-label2);
  border-radius: var(--sv-r-tag);
  padding: 1px 6px;
}
.none {
  text-align: center;
  padding: var(--sv-s4);
}
</style>
