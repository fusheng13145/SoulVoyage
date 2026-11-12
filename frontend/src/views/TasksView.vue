<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvSheet from '@/components/ui/SvSheet.vue'
import { taskApi, type TaskItem, type TaskStep } from '@/api/domains/task'
import { streamTask } from '@/api/sse'
import { AGENT_LABELS, PIPELINE_LABELS, TASK_STATUS_LABELS } from '@/constants/task'
import { toast } from '@/stores/ui'

/** H-01 任务中心：历史、失败原因、步骤时间线、断点重放与失败重试。 */

const FILTERS = [
  { label: '全部', value: '' },
  { label: '进行中', value: 'RUNNING' },
  { label: '失败', value: 'FAILED' },
  { label: '已完成', value: 'SUCCESS' },
] as const

const items = ref<TaskItem[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(true)
const filter = ref('')
const sheetOpen = ref(false)
const detail = ref<TaskItem | null>(null)
const steps = ref<TaskStep[]>([])
const stepsLoading = ref(false)
const following = ref(false)
const retrying = ref(false)
let abort: AbortController | null = null

const canRetry = computed(() => detail.value?.status === 'FAILED')
const canFollow = computed(
  () => !!detail.value && (detail.value.status === 'PENDING' || detail.value.status === 'RUNNING'),
)

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', { hour12: false })
}

function statusLabel(s: string): string {
  return TASK_STATUS_LABELS[s] || s
}

function statusCls(s: string): string {
  return `st-${s.toLowerCase()}`
}

async function load(reset = true) {
  if (reset) {
    page.value = 0
    loading.value = true
  }
  try {
    const data = await taskApi.list<{ items: TaskItem[]; total?: number }>({
      page: page.value,
      size: 20,
      status: filter.value || undefined,
    })
    items.value = reset ? data.items : [...items.value, ...data.items]
    total.value = data.total ?? items.value.length
  } catch (e) {
    toast((e as Error).message || '任务列表没拉起来')
  } finally {
    loading.value = false
  }
}

function setFilter(value: string) {
  if (filter.value === value) return
  filter.value = value
  load(true)
}

function loadMore() {
  page.value += 1
  load(false)
}

async function loadSteps() {
  if (!detail.value) return
  stepsLoading.value = true
  try {
    steps.value = await taskApi.steps(detail.value.taskNo)
  } catch {
    /* 时间线失败不阻断详情 */
  } finally {
    stepsLoading.value = false
  }
}

function stopFollow() {
  if (abort) {
    abort.abort()
    abort = null
  }
  following.value = false
}

async function follow() {
  const t = detail.value
  if (!t || !canFollow.value) return
  stopFollow()
  abort = new AbortController()
  following.value = true
  try {
    await streamTask(
      t.taskNo,
      e => {
        if (!detail.value || detail.value.taskNo !== t.taskNo) return
        if (['step_started', 'step_retry', 'middle_result', 'step_failed'].includes(e.event)) {
          loadSteps()
        }
        if (e.event === 'task_status') {
          const st = (e.data as { status?: string }).status
          if (st) detail.value.status = st
        }
        if (e.event === 'done' || e.event === 'error') {
          loadSteps()
          load(false)
        }
      },
      abort.signal,
    )
  } catch {
    /* 主动取消或断流：状态由下一次刷新兜底 */
  } finally {
    if (detail.value?.taskNo === t.taskNo) following.value = false
  }
}

async function open(t: TaskItem) {
  detail.value = t
  steps.value = []
  sheetOpen.value = true
  await loadSteps()
  if (canFollow.value) follow()
}

async function retry() {
  const t = detail.value
  if (!t || retrying.value || !canRetry.value) return
  retrying.value = true
  try {
    await taskApi.retry(t.taskNo)
    toast('已重新排队，接着上次失败的地方继续')
    t.status = 'RUNNING'
    await loadSteps()
    load(false)
    follow()
  } catch (e) {
    toast((e as Error).message || '重试没成功，稍后再试')
  } finally {
    retrying.value = false
  }
}

onMounted(() => load(true))
onUnmounted(stopFollow)
</script>

<template>
  <div class="tasks">
    <SvNavBar title="任务中心" back="返回" :large="true" />
    <SvPageBody>
      <div class="filters" role="tablist" aria-label="任务筛选">
        <button
          v-for="f in FILTERS"
          :key="f.value"
          class="chip"
          :class="{ on: filter === f.value }"
          role="tab"
          :aria-selected="filter === f.value"
          @click="setFilter(f.value)"
        >
          {{ f.label }}
        </button>
      </div>

      <p class="lead sv-muted">
        这里能看见系统为你跑过的每一步。失败不会吞掉结果，可以点开看原因，也可以从失败处继续。
      </p>

      <div v-if="loading && !items.length" class="empty sv-muted">正在整理你的任务…</div>
      <div v-else-if="!items.length" class="empty">
        <p class="sv-muted">还没有任务记录。写下第一篇日记，这里就会出现系统为你做的事。</p>
      </div>

      <button v-for="t in items" :key="t.taskNo" class="row" @click="open(t)">
        <div class="row-main">
          <div class="row-title">{{ PIPELINE_LABELS[t.pipelineCode] || t.pipelineCode }}</div>
          <div class="row-meta sv-muted">
            <span>{{ fmt(t.createdAt) }}</span>
            <span v-if="t.errorMsg" class="row-err">· {{ t.errorMsg }}</span>
          </div>
        </div>
        <span class="st" :class="statusCls(t.status)">{{ statusLabel(t.status) }}</span>
        <span class="chev" aria-hidden="true">›</span>
      </button>

      <button v-if="items.length < total" class="sv-btn ghost more" @click="loadMore">
        加载更多
      </button>
    </SvPageBody>

    <SvSheet
      v-model="sheetOpen"
      :title="detail ? PIPELINE_LABELS[detail.pipelineCode] || '任务详情' : '任务详情'"
    >
      <template v-if="detail">
        <div class="head">
          <span class="st" :class="statusCls(detail.status)">{{ statusLabel(detail.status) }}</span>
          <span v-if="following" class="live">实时跟随中</span>
        </div>
        <dl class="meta">
          <div><dt>任务号</dt><dd>{{ detail.taskNo }}</dd></div>
          <div><dt>创建</dt><dd>{{ fmt(detail.createdAt) }}</dd></div>
          <div><dt>结束</dt><dd>{{ fmt(detail.finishedAt) }}</dd></div>
        </dl>
        <p v-if="detail.errorMsg" class="error">{{ detail.errorMsg }}</p>

        <h3 class="sec">步骤时间线</h3>
        <p v-if="stepsLoading && !steps.length" class="sv-muted">读取中…</p>
        <p v-else-if="!steps.length" class="sv-muted">这次任务还没有步骤记录。</p>
        <ol v-else class="timeline">
          <li v-for="s in steps" :key="`${s.stepSeq}-${s.attempt}-${s.createdAt}`" class="step">
            <span class="dot" :class="statusCls(s.status)" aria-hidden="true" />
            <div class="step-main">
              <div class="step-head">
                <strong>{{ AGENT_LABELS[s.agent] || s.agent }}</strong>
                <span class="step-st sv-muted">{{ s.status }}</span>
              </div>
              <div class="step-meta sv-muted">
                第 {{ s.stepSeq }} 步
                <span v-if="s.attempt > 1"> · 第 {{ s.attempt }} 次尝试</span>
                <span v-if="s.costMs != null"> · {{ s.costMs }}ms</span>
                <span> · {{ fmt(s.createdAt) }}</span>
              </div>
              <p v-if="s.errorMsg" class="step-err">{{ s.errorMsg }}</p>
            </div>
          </li>
        </ol>

        <div class="actions">
          <button v-if="canFollow && !following" class="sv-btn" @click="follow">继续接收进度</button>
          <button v-if="canRetry" class="sv-btn" :disabled="retrying" @click="retry">
            {{ retrying ? '正在重试…' : '从失败处重试' }}
          </button>
        </div>
      </template>
    </SvSheet>
  </div>
</template>

<style scoped>
.tasks {
  min-height: 100%;
}
.filters {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 2px 0 10px;
}
.chip {
  border: 1px solid var(--sv-sep);
  background: var(--sv-bg);
  color: var(--sv-label2);
  border-radius: 999px;
  padding: 6px 14px;
  font-size: var(--sv-fs-footnote);
  white-space: nowrap;
}
.chip.on {
  border-color: var(--sv-indigo);
  color: var(--sv-indigo);
  background: var(--sv-indigo-soft);
}
.lead {
  font-size: var(--sv-fs-footnote);
  line-height: 1.6;
  margin: 0 2px 14px;
}
.empty {
  padding: 42px 10px;
  text-align: center;
  line-height: 1.7;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: none;
  border-bottom: 1px solid var(--sv-sep);
  background: transparent;
  padding: 14px 2px;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-title {
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
}
.row-meta {
  margin-top: 4px;
  font-size: var(--sv-fs-caption);
  display: flex;
  gap: 6px;
  min-width: 0;
}
.row-err {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.st {
  flex: none;
  font-size: var(--sv-fs-caption);
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--sv-sep);
  color: var(--sv-label2);
}
.st-success {
  color: var(--sv-mint);
  border-color: color-mix(in srgb, var(--sv-mint) 32%, transparent);
}
.st-partial_success {
  color: var(--sv-amber);
  border-color: color-mix(in srgb, var(--sv-amber) 36%, transparent);
}
.st-failed {
  color: var(--sv-red);
  border-color: color-mix(in srgb, var(--sv-red) 32%, transparent);
}
.st-running,
.st-pending {
  color: var(--sv-indigo);
  border-color: color-mix(in srgb, var(--sv-indigo) 32%, transparent);
}
.chev {
  color: var(--sv-label3);
  font-size: 20px;
  line-height: 1;
}
.more {
  width: 100%;
  margin-top: 16px;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.live {
  font-size: var(--sv-fs-caption);
  color: var(--sv-indigo);
}
.meta {
  display: grid;
  gap: 6px;
  margin: 0 0 12px;
}
.meta div {
  display: flex;
  gap: 10px;
  font-size: var(--sv-fs-footnote);
}
.meta dt {
  color: var(--sv-label3);
  min-width: 44px;
}
.meta dd {
  margin: 0;
  color: var(--sv-label2);
  word-break: break-all;
}
.error {
  margin: 0 0 14px;
  padding: 10px 12px;
  border-radius: var(--sv-radius-m);
  background: color-mix(in srgb, var(--sv-red) 8%, transparent);
  color: var(--sv-red);
  font-size: var(--sv-fs-footnote);
  line-height: 1.6;
}
.sec {
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-body);
  margin: 6px 0 10px;
}
.timeline {
  list-style: none;
  margin: 0;
  padding: 0 0 4px;
}
.step {
  display: flex;
  gap: 10px;
  padding-bottom: 14px;
  position: relative;
}
.step:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: var(--sv-sep);
}
.dot {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-top: 4px;
  background: var(--sv-label3);
}
.dot.st-success {
  background: var(--sv-mint);
  border: none;
}
.dot.st-partial_success,
.dot.st-failed {
  background: var(--sv-amber);
  border: none;
}
.dot.st-failed {
  background: var(--sv-red);
}
.dot.st-running,
.dot.st-pending {
  background: var(--sv-indigo);
  border: none;
}
.step-main {
  flex: 1;
  min-width: 0;
}
.step-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label);
}
.step-st {
  font-size: var(--sv-fs-caption);
}
.step-meta {
  margin-top: 2px;
  font-size: var(--sv-fs-caption);
  line-height: 1.5;
}
.step-err {
  margin: 6px 0 0;
  font-size: var(--sv-fs-caption);
  line-height: 1.6;
  color: var(--sv-red);
}
.actions {
  display: grid;
  gap: 10px;
  margin-top: 8px;
}
</style>
