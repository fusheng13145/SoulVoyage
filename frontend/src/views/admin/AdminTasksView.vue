<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import { listTasks, getTask, retryTask, type TaskRow, type TaskDetail } from '@/api/admin'
import { confirmDialog, toast } from '@/stores/ui'

const rows = ref<TaskRow[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const status = ref('')
const pipeline = ref('')
const loading = ref(true)
const detail = ref<TaskDetail | null>(null)
const detailOpen = ref(false)

const STATUS_TONE: Record<string, string> = {
  SUCCESS: 'ok',
  PARTIAL_SUCCESS: 'warn',
  FAILED: 'bad',
  RUNNING: 'run',
  PENDING: 'run',
}

async function load(p = page.value) {
  loading.value = true
  try {
    const r = await listTasks({
      status: status.value || undefined,
      pipelineCode: pipeline.value || undefined,
      page: p,
      size,
    })
    rows.value = r.items
    total.value = r.total
    page.value = r.page
  } catch (e) {
    toast((e as Error).message || '任务列表没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function openDetail(t: TaskRow) {
  try {
    detail.value = await getTask(t.taskNo)
    detailOpen.value = true
  } catch (e) {
    toast((e as Error).message || '详情没拉起来')
  }
}

async function retry(t: TaskRow) {
  const ok = await confirmDialog({
    title: '重跑失败任务',
    message: `将从首个失败步骤续跑 ${t.taskNo}，复用已落库的中间产物。确认执行？`,
    confirmText: '重跑',
  })
  if (!ok) return
  try {
    await retryTask(t.taskNo)
    toast('已提交重跑')
    load()
  } catch (e) {
    toast((e as Error).message || '重跑没提交成功')
  }
}

const pages = () => Math.max(1, Math.ceil(total.value / size))
const fmt = (s: string) => (s ? s.replace('T', ' ').slice(0, 19) : '—')
</script>

<template>
  <AdminShell title="任务监控" subtitle="A1 · 全局任务与失败重跑">
    <div class="filters">
      <select v-model="pipeline" @change="load(0)">
        <option value="">全部管线</option>
        <option value="DIARY_PIPELINE">日记</option>
        <option value="SUPPORT_PIPELINE">陪伴</option>
        <option value="COMPANION_PIPELINE">消化</option>
        <option value="REVIEW_PIPELINE">复盘</option>
        <option value="GROWTH_LETTER_PIPELINE">来信</option>
      </select>
      <select v-model="status" @change="load(0)">
        <option value="">全部状态</option>
        <option
          v-for="s in ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'RUNNING', 'PENDING']"
          :key="s"
          :value="s"
        >
          {{ s }}
        </option>
      </select>
      <span class="muted">{{ total }} 条</span>
    </div>

    <p v-if="loading" class="muted center">加载中…</p>
    <SvCard v-else-if="!rows.length" class="empty">
      <p class="muted center">没有符合条件的任务。</p>
    </SvCard>
    <template v-else>
      <SvCard v-for="t in rows" :key="t.taskNo" :pad="false">
        <button class="row" @click="openDetail(t)">
          <span class="pill" :class="STATUS_TONE[t.status] || 'run'">{{ t.status }}</span>
          <span class="r-txt">
            <b>{{ t.taskNo }}</b>
            <small>{{ t.pipelineCode }} · 用户 #{{ t.userId }} · {{ fmt(t.createdAt) }}</small>
            <small v-if="t.errorMsg" class="err">{{ t.errorMsg }}</small>
          </span>
          <SvIcon name="i-arrow" :size="16" tone="label2" />
        </button>
        <div v-if="t.status === 'FAILED'" class="ops">
          <button class="btn" @click="retry(t)">
            <SvIcon name="i-refresh" :size="14" tone="inherit" /> 从失败步重跑
          </button>
        </div>
      </SvCard>

      <div class="pager">
        <button class="pg" :disabled="page === 0" @click="load(0)">首页</button>
        <span class="muted">{{ page + 1 }} / {{ pages() }}</span>
        <button class="pg" :disabled="page + 1 >= pages()" @click="load(page + 1)">下一页</button>
      </div>
    </template>

    <!-- 步骤时间线 -->
    <div v-if="detailOpen" class="mask" @click.self="detailOpen = false">
      <SvCard class="sheet">
        <div class="sh-hd">
          <b>{{ detail?.taskNo }}</b>
          <button class="x" aria-label="关闭" @click="detailOpen = false">
            <SvIcon name="i-close" :size="16" tone="inherit" />
          </button>
        </div>
        <p v-if="detail" class="sh-meta">
          {{ detail.pipelineCode }} · {{ detail.status }} · 用户 #{{ detail.userId }}<br />
          创建 {{ fmt(detail.createdAt) }} → 开始 {{ fmt(detail.startedAt) }} → 结束
          {{ fmt(detail.finishedAt) }}
        </p>
        <p v-if="detail?.errorMsg" class="err sh-err">{{ detail.errorMsg }}</p>
        <ol v-if="detail" class="steps">
          <li v-for="s in detail.steps" :key="`${s.stepSeq}-${s.attempt}`" :class="s.status.toLowerCase()">
            <div class="st-row">
              <b>#{{ s.stepSeq }} {{ s.agentCode }} · {{ s.stepCode }}</b>
              <span class="pill sm" :class="STATUS_TONE[s.status] || 'run'">{{ s.status }}</span>
              <em v-if="s.attempt > 1" class="muted">第 {{ s.attempt }} 次</em>
            </div>
            <small class="muted">
              {{ s.costMs ?? '—' }}ms · LLM {{ s.llmCalls ?? 0 }} · token {{ s.tokensIn ?? 0 }}/{{
                s.tokensOut ?? 0
              }}
              · {{ s.model || '—' }} · {{ fmt(s.createdAt) }}
            </small>
            <small v-if="s.errorMsg" class="err"> {{ s.errorMsg }}</small>
          </li>
        </ol>
      </SvCard>
    </div>
  </AdminShell>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: var(--sv-s4);
}
.filters select {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 10px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
}
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
}
.err {
  color: var(--sv-red, #d64545);
  font-size: var(--sv-fs-caption2);
}
.row {
  display: flex;
  gap: var(--sv-s3);
  align-items: center;
  width: 100%;
  padding: 13px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--sv-label);
  font-family: inherit;
}
.r-txt {
  flex: 1;
  min-width: 0;
}
.r-txt b {
  font-size: var(--sv-fs-footnote);
  display: block;
  word-break: break-all;
}
.r-txt small {
  display: block;
  margin-top: 3px;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
}
.pill {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  flex: none;
}
.pill.ok {
  background: color-mix(in srgb, #30d158 18%, transparent);
  color: #1d7a3a;
}
.pill.warn {
  background: color-mix(in srgb, #ff9f0a 20%, transparent);
  color: #8a5a00;
}
.pill.bad {
  background: color-mix(in srgb, #d64545 16%, transparent);
  color: #b03030;
}
.pill.run {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
}
.pill.sm {
  font-size: 9px;
  padding: 2px 6px;
}
[data-theme='dark'] .pill.ok {
  color: #6ee787;
}
[data-theme='dark'] .pill.warn {
  color: #ffd28a;
}
[data-theme='dark'] .pill.bad {
  color: #ff9d9d;
}
.ops {
  padding: 0 14px 12px;
}
.btn {
  border: 1px solid var(--sv-sep);
  background: transparent;
  color: var(--sv-indigo);
  border-radius: 10px;
  padding: 7px 12px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.pager {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 14px;
  margin: var(--sv-s4) 0;
}
.pg {
  border: none;
  background: transparent;
  color: var(--sv-indigo);
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  padding: 8px;
}
.pg:disabled {
  color: var(--sv-label3);
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 60;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.sheet {
  width: 100%;
  max-width: 620px;
  max-height: 78dvh;
  overflow-y: auto;
  margin: 0 auto 12px;
}
.sh-hd {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.sh-hd b {
  font-size: var(--sv-fs-subhead);
  word-break: break-all;
}
.x {
  border: none;
  background: transparent;
  color: var(--sv-label3);
  cursor: pointer;
  padding: 6px;
  display: grid;
  place-items: center;
}
.sh-meta {
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
  line-height: 1.7;
  margin: 0 0 8px;
}
.sh-err {
  margin-bottom: 8px;
}
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.steps li {
  border-left: 3px solid var(--sv-sep);
  padding-left: 10px;
}
.steps li.success {
  border-color: #30d158;
}
.steps li.failed {
  border-color: #d64545;
}
.steps li.running {
  border-color: var(--sv-indigo);
}
.st-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.st-row b {
  font-size: var(--sv-fs-footnote);
}
.steps small {
  display: block;
  margin-top: 2px;
  line-height: 1.6;
}
</style>
