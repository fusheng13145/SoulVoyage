<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import { getOverview, type Overview } from '@/api/admin'
import { toast } from '@/stores/ui'

const data = ref<Overview | null>(null)
const days = ref(7)
const loading = ref(true)

const STATUS_LABEL: Record<string, string> = {
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  RUNNING: '进行中',
  PENDING: '排队',
}
const LEVEL_LABEL: Record<string, string> = { CRISIS: '危机', HIGH: '高', MEDIUM: '中', LOW: '低' }

async function load() {
  loading.value = true
  try {
    data.value = await getOverview(days.value)
  } catch (e) {
    toast((e as Error).message || '指标没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)

const dayOptions = [1, 7, 30, 90]
function setDays(d: number) {
  days.value = d
  load()
}

const taskCards = computed(() => {
  const o = data.value
  if (!o) return []
  return [
    { label: '今日任务', value: sum(o.tasks.today.byStatus), sub: statusMix(o.tasks.today.byStatus) },
    {
      label: `窗口完成（${o.windowDays}天）`,
      value: o.tasks.window.finished,
      sub: statusMix(o.tasks.window.byStatus),
    },
    {
      label: '任务成功率',
      value: o.tasks.window.successRate == null ? '—' : `${o.tasks.window.successRate}%`,
      sub: '成功+部分成功 / 已完成',
    },
    { label: '风险事件', value: sum(o.riskEvents), sub: statusMix(o.riskEvents, LEVEL_LABEL) },
  ]
})

const tokenMax = computed(() => {
  const daysList = data.value?.tokensByDay ?? []
  return Math.max(1, ...daysList.map(d => d.tokensIn + d.tokensOut))
})

function sum(m: Record<string, number>) {
  return Object.values(m).reduce((a, b) => a + b, 0)
}
function statusMix(m: Record<string, number>, labels = STATUS_LABEL) {
  const parts = Object.entries(m)
    .filter(([, v]) => v > 0)
    .map(([k, v]) => `${labels[k] || k} ${v}`)
  return parts.length ? parts.join(' · ') : '暂无'
}
const fmt = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString())
</script>

<template>
  <AdminShell title="管理端总览" subtitle="观测与成本">
    <div class="seg">
      <button
        v-for="d in dayOptions"
        :key="d"
        class="seg-btn"
        :class="{ on: days === d }"
        @click="setDays(d)"
      >
        近{{ d }}天
      </button>
    </div>

    <p v-if="loading" class="muted center">加载中…</p>
    <template v-else-if="data">
      <div class="grid">
        <SvCard v-for="c in taskCards" :key="c.label" class="stat">
          <small>{{ c.label }}</small>
          <b>{{ c.value }}</b>
          <em>{{ c.sub }}</em>
        </SvCard>
      </div>

      <SvCard class="chart">
        <h3>Token 消耗（按日）</h3>
        <p v-if="!data.tokensByDay.length" class="muted">窗口内没有 LLM 调用记录。</p>
        <div v-else class="bars">
          <div
            v-for="t in data.tokensByDay"
            :key="t.date"
            class="bcol"
            :title="`${t.date} 入${t.tokensIn} 出${t.tokensOut}`"
          >
            <div class="bar">
              <div class="b-in" :style="{ height: `${(t.tokensIn / tokenMax) * 100}%` }" />
              <div class="b-out" :style="{ height: `${(t.tokensOut / tokenMax) * 100}%` }" />
            </div>
            <small>{{ t.date.slice(5) }}</small>
          </div>
        </div>
        <p class="legend"><i class="sq in" /> 输入 <i class="sq out" /> 输出</p>
      </SvCard>

      <SvCard :pad="false">
        <h3 class="hd">Agent 成本（{{ data.windowDays }} 天）</h3>
        <table v-if="data.agents.length">
          <thead>
            <tr>
              <th>Agent</th>
              <th>步数</th>
              <th>均耗</th>
              <th>P95</th>
              <th>LLM</th>
              <th>Token 入/出</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in data.agents" :key="a.agent">
              <td>{{ a.agent }}</td>
              <td>{{ a.steps }}</td>
              <td>{{ fmt(a.avgMs) }}ms</td>
              <td>{{ fmt(a.p95Ms) }}ms</td>
              <td>{{ fmt(a.llmCalls) }}</td>
              <td>{{ fmt(a.tokensIn) }} / {{ fmt(a.tokensOut) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted pad">窗口内没有步骤日志。</p>
      </SvCard>

      <SvCard class="stat">
        <h3>进程内实时计数（重启清零）</h3>
        <p class="rt">
          提交 {{ fmt(data.runtime.taskSubmitted) }} · LLM 调用 {{ fmt(data.runtime.llmCalls) }} · Token 入
          {{ fmt(data.runtime.tokensIn) }} / 出 {{ fmt(data.runtime.tokensOut) }}
        </p>
        <p class="muted">本实例推理闸门：{{ data.runtime.llmGate }}</p>
      </SvCard>
    </template>
  </AdminShell>
</template>

<style scoped>
.seg {
  display: flex;
  gap: 6px;
  margin-bottom: var(--sv-s4);
}
.seg-btn {
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
}
.seg-btn.on {
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
  color: #fff;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.stat small {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
  display: block;
}
.stat b {
  font-size: var(--sv-fs-title2);
  display: block;
  margin-top: 4px;
}
.stat em {
  font-style: normal;
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption2);
  display: block;
  margin-top: 4px;
  line-height: 1.5;
}
.stat h3 {
  font-size: var(--sv-fs-subhead);
  margin-bottom: 6px;
}
.rt {
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  line-height: 1.7;
  margin: 0;
}
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-footnote);
}
.chart h3 {
  font-size: var(--sv-fs-subhead);
  margin-bottom: var(--sv-s3);
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 130px;
  overflow-x: auto;
}
.bcol {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 26px;
  height: 100%;
  justify-content: flex-end;
}
.bcol small {
  font-size: 9px;
  color: var(--sv-label3);
  white-space: nowrap;
}
.bar {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  width: 100%;
  max-width: 26px;
  flex: 1;
  min-height: 2px;
}
.b-in {
  background: var(--sv-indigo);
  border-radius: 3px 3px 0 0;
  min-height: 0;
}
.b-out {
  background: color-mix(in srgb, var(--sv-indigo) 35%, transparent);
  border-radius: 0;
}
.bar {
  border-radius: 4px 4px 0 0;
  overflow: hidden;
}
.legend {
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label3);
  margin: 8px 0 0;
  display: flex;
  align-items: center;
  gap: 4px;
}
.sq {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-left: 8px;
}
.sq.in {
  background: var(--sv-indigo);
  margin-left: 0;
}
.sq.out {
  background: color-mix(in srgb, var(--sv-indigo) 35%, transparent);
}
.hd {
  font-size: var(--sv-fs-subhead);
  padding: 14px 14px 6px;
}
.pad {
  padding: 14px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--sv-fs-caption1);
}
th {
  text-align: left;
  color: var(--sv-label3);
  font-weight: 500;
  padding: 6px 10px;
  border-bottom: 1px solid var(--sv-sep);
  white-space: nowrap;
}
td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--sv-sep);
  color: var(--sv-label2);
  white-space: nowrap;
}
tr:last-child td {
  border-bottom: none;
}
</style>
