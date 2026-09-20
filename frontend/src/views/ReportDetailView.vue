<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvChip from '@/components/ui/SvChip.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import http, { type ApiResp, type Json } from '@/api/http'
import { toast } from '@/stores/ui'
import { useContentStore } from '@/stores/content'

const route = useRoute()
const content = useContentStore()

interface ReportDetail {
  id: number
  type: string
  title: string
  riskLevel: string
  createdAt: string
  content: Json
}
const report = ref<ReportDetail | null>(null)
const loading = ref(true)
const expanded = ref(false)

const TYPE_LABELS: Record<string, string> = {
  TRACE: '溯源复盘',
  DIARY_TRACE: '溯源复盘',
  DIARY_REPORT: '复盘报告',
  SIMULATE: '沟通复盘',
  SIMULATE_REVIEW: '沟通复盘',
  SUPPORT: '自助方案',
  WEEKLY: '周报',
}
const REPORT_TITLES: Record<string, string> = {
  eventSummary: '事件',
  emotionSummary: '情绪',
  thoughtSummary: '想法',
  insight: '洞察',
  suggestion: '下一步',
}
const DIM_LABELS: Record<string, string> = {
  LISTEN: '倾听',
  BOUNDARY: '边界表达',
  EMPATHY: '共情',
  CONCESSION: '让步策略',
}
const gradeCls = (g: string) => ({ A: 'g-a', B: 'g-b', C: 'g-c', D: 'g-d' })[g] || 'g-c'

const isTrace = computed(
  () => report.value && (report.value.type === 'TRACE' || report.value.type === 'DIARY_TRACE'),
)
const isDiaryReport = computed(() => report.value?.type === 'DIARY_REPORT')
const isSupport = computed(() => report.value?.type === 'SUPPORT')
const isSimulate = computed(
  () => report.value && (report.value.type === 'SIMULATE' || report.value.type === 'SIMULATE_REVIEW'),
)
const riskChip = computed(
  () =>
    (
      ({
        HIGH: {
          label: '需要关照',
          tone: 'color-mix(in srgb, var(--sv-red) 16%, transparent)',
          color: 'var(--sv-red)',
        },
        MEDIUM: {
          label: '情绪起伏',
          tone: 'color-mix(in srgb, var(--sv-amber) 18%, transparent)',
          color: 'var(--sv-amber)',
        },
      }) as Record<string, { label: string; tone: string; color: string }>
    )[report.value?.riskLevel || ''] || {
      label: '平稳',
      tone: 'color-mix(in srgb, var(--sv-mint) 14%, transparent)',
      color: 'var(--sv-mint)',
    },
)

const exerciseNames = computed(() => Object.fromEntries((content.exercises ?? []).map(x => [x.id, x])))

/** 溯源报告正文可能挂在 content 顶层或 content.report */
const traceBody = computed(() => report.value?.content?.report ?? report.value?.content ?? {})

onMounted(async () => {
  content.ensureExercises().catch(() => {
    /* 名称降级为 id */
  })
  try {
    const { data } = await http.get<ApiResp<ReportDetail>>(`/reports/${route.params.id}`)
    report.value = data.data
  } catch (e) {
    toast((e as Error).message || '报告不存在或已删除')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="report">
    <SvNavBar :title="report ? TYPE_LABELS[report.type] || '报告' : '报告'" back="返回" :large="false" />

    <div v-if="report" class="body">
      <SvCard>
        <div class="head">
          <h2>{{ report.title }}</h2>
          <SvChip :model-value="true" :tone="riskChip.tone" :color="riskChip.color" class="ro">{{
            riskChip.label
          }}</SvChip>
        </div>
        <p class="sv-cap">
          {{ report.createdAt ? report.createdAt.slice(0, 16).replace('T', ' ') : '' }} ·
          {{ TYPE_LABELS[report.type] || report.type }}
        </p>
      </SvCard>

      <!-- 溯源 / 日记报告：事件-想法-情绪 + 压力源 + 误区 + 苏格拉底问题 -->
      <template v-if="isTrace || isDiaryReport">
        <SvCard v-if="traceBody.report || traceBody.insight">
          <h3 class="t">复盘正文</h3>
          <dl class="report-dl">
            <template v-for="(v, k) in traceBody.report || traceBody" :key="k">
              <template v-if="REPORT_TITLES[k as string] && typeof v === 'string'">
                <dt>{{ REPORT_TITLES[k as string] }}</dt>
                <dd>{{ v }}</dd>
              </template>
            </template>
          </dl>
        </SvCard>
        <SvCard v-if="traceBody.stressors?.length">
          <h3 class="t">压力来源</h3>
          <ul class="plain">
            <li v-for="s in traceBody.stressors" :key="s.source">
              <b>{{ s.source }}</b
              ><em class="conf">{{ Math.round((s.confidence ?? 0) * 100) }}%</em>
              <span v-for="(ev, i) in s.evidence" :key="i" class="ev sv-muted">「{{ ev }}」</span>
            </li>
          </ul>
        </SvCard>
        <SvCard v-if="traceBody.cognitiveDistortions?.length">
          <h3 class="t">思维误区线索</h3>
          <div v-for="d in traceBody.cognitiveDistortions" :key="d.kgNodeId || d.name" class="dist">
            <b>{{ d.name }}</b>
            <p class="sv-muted">触发：{{ d.trigger }}</p>
            <p class="q">🤔 {{ d.challengeQuestion }}</p>
          </div>
        </SvCard>
        <SvCard v-if="traceBody.socraticQuestions?.length">
          <h3 class="t">留给你慢慢想的问题</h3>
          <ol class="socratic">
            <li v-for="(q, i) in traceBody.socraticQuestions" :key="i">{{ q }}</li>
          </ol>
        </SvCard>
      </template>

      <!-- 疏导方案 -->
      <SvCard v-if="isSupport">
        <h3 class="t">🌿 {{ report.content.planTitle }}</h3>
        <div v-for="m in report.content.matchedExercises" :key="m.exerciseId" class="plan-item">
          <div class="pi-head">
            <b>{{ exerciseNames[m.exerciseId]?.name || m.exerciseId }}</b>
            <em class="when">{{ m.schedule }}</em>
          </div>
          <p>{{ m.reason }}</p>
          <small v-if="exerciseNames[m.exerciseId]" class="sv-muted">
            {{ exerciseNames[m.exerciseId].steps.map((s: any) => s.step).join(' → ') }} · 约
            {{ exerciseNames[m.exerciseId].durationMin }} 分钟</small
          >
        </div>
        <div v-if="report.content.psyEducation" class="edu">
          <b>心理小课堂 · {{ report.content.psyEducation.topic }}</b>
          <p class="sv-muted">{{ report.content.psyEducation.content }}</p>
        </div>
      </SvCard>

      <!-- 模拟复盘 -->
      <SvCard v-if="isSimulate">
        <div class="score-line">
          <b class="avg">{{ report.content.overall?.avgScore ?? '--' }}</b>
          <span class="sv-muted">综合沟通分 · NVO 四要素</span>
        </div>
        <p class="advice">{{ report.content.overallAdvice }}</p>
        <div class="dim-scores">
          <span v-for="d in DIM_LABELS" :key="d" class="dim-chip">{{ d }}</span>
        </div>
        <div v-for="ts in report.content.turnScores" :key="ts.turn" class="tscore">
          <span class="grade" :class="gradeCls(ts.grade)">{{ ts.grade }}</span>
          <b>第 {{ ts.turn }} 轮 · {{ DIM_LABELS[ts.dimension] || ts.dimension }}</b>
          <p class="sv-muted">{{ ts.comment }}</p>
        </div>
        <template v-if="report.content.rewriteSuggestions?.length">
          <h4>换个说法（NVO 改写）</h4>
          <div v-for="(r, i) in report.content.rewriteSuggestions" :key="i" class="rewrite">
            <p class="from sv-muted">原话（第 {{ r.turn }} 轮）：{{ r.original }}</p>
            <p class="to">建议：{{ r.optimized }}</p>
          </div>
        </template>
      </SvCard>

      <!-- 未识别类型：原文折叠兜底 -->
      <SvCard v-if="!isTrace && !isDiaryReport && !isSupport && !isSimulate">
        <button class="toggle" :aria-expanded="expanded" @click="expanded = !expanded">
          {{ expanded ? '收起正文' : '查看报告正文' }}
        </button>
        <pre v-if="expanded" class="raw sv-muted">{{ JSON.stringify(report.content, null, 2) }}</pre>
      </SvCard>

      <SvDisclaimer
        text="报告基于你自己的记录由 AI 生成，是自助参考，不构成任何诊断"
        :reason="'结论对照心理学知识库（CBG 图谱）与你的原始记录推导，只描述可能性。所有原文信封加密，仅你可见。'"
      />
    </div>

    <div v-else-if="loading" class="body"><p class="sv-muted none">报告解密中…</p></div>
    <div v-else class="body"><p class="sv-muted none">报告不存在、已删除或属于其他账号。</p></div>
  </div>
</template>

<style scoped>
.body {
  padding: 0 var(--sv-s4);
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: space-between;
}
.head h2 {
  font-size: var(--sv-fs-title3);
  margin: 0;
}
.ro {
  cursor: default;
  height: auto;
  flex: none;
}
.t {
  font-size: var(--sv-fs-callout);
  margin-bottom: var(--sv-s2);
}
.report-dl {
  font-size: var(--sv-fs-subhead);
}
.report-dl dt {
  float: left;
  clear: left;
  width: 4.5em;
  color: var(--sv-label2);
}
.report-dl dd {
  margin: 0 0 8px 5em;
  line-height: 1.7;
}
.plain li {
  padding: 8px 0;
  border-bottom: 1px dashed var(--sv-sep);
  font-size: var(--sv-fs-subhead);
}
.plain li:last-child {
  border-bottom: none;
}
.conf {
  font-style: normal;
  margin-left: 8px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-indigo);
  background: var(--sv-indigo-soft);
  border-radius: var(--sv-r-pill);
  padding: 1px 8px;
}
.ev {
  display: block;
  font-size: var(--sv-fs-caption1);
}
.dist {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  font-size: var(--sv-fs-subhead);
}
.dist .q {
  margin-top: 6px;
}
.socratic {
  padding-left: 22px;
  list-style: decimal;
  font-size: var(--sv-fs-subhead);
  line-height: 1.8;
}
.plan-item {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  font-size: var(--sv-fs-subhead);
}
.pi-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.plan-item b {
  color: var(--sv-mint);
}
.plan-item p {
  margin: 4px 0;
}
.when {
  font-style: normal;
  font-size: var(--sv-fs-caption1);
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
  border-radius: var(--sv-r-pill);
  padding: 1px 8px;
}
.edu {
  margin-top: var(--sv-s3);
  background: var(--sv-fill3);
  border-radius: var(--sv-r-ctl);
  padding: 12px 14px;
}
.edu b {
  font-size: var(--sv-fs-subhead);
}
.edu p {
  margin-top: 4px;
  line-height: 1.7;
}
.score-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.avg {
  font-size: 40px;
  font-weight: 700;
  color: var(--sv-indigo);
  font-variant-numeric: tabular-nums;
}
.advice {
  margin: var(--sv-s2) 0;
  font-size: var(--sv-fs-subhead);
  line-height: 1.7;
}
.dim-scores {
  display: flex;
  gap: 6px;
  margin-bottom: var(--sv-s3);
  flex-wrap: wrap;
}
.dim-chip {
  font-size: var(--sv-fs-caption1);
  background: var(--sv-fill2);
  color: var(--sv-label2);
  border-radius: var(--sv-r-pill);
  padding: 2px 10px;
}
h4 {
  margin: var(--sv-s4) 0 var(--sv-s2);
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
}
.tscore {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  font-size: var(--sv-fs-footnote);
}
.tscore b {
  margin-left: 6px;
}
.grade {
  display: inline-block;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 6px;
  color: #fff;
  font-weight: 700;
  font-size: var(--sv-fs-footnote);
}
.g-a {
  background: var(--sv-mint);
}
.g-b {
  background: var(--sv-blue);
}
.g-c {
  background: var(--sv-amber);
}
.g-d {
  background: var(--sv-red);
}
.rewrite {
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  background: color-mix(in srgb, var(--sv-mint) 8%, var(--sv-card));
  font-size: var(--sv-fs-footnote);
}
.rewrite .from {
  text-decoration: line-through;
}
.rewrite .to {
  color: var(--sv-mint);
  margin-top: 4px;
}
.toggle {
  border: none;
  background: var(--sv-fill2);
  color: var(--sv-indigo);
  border-radius: var(--sv-r-pill);
  padding: 8px 16px;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--sv-fs-footnote);
}
.raw {
  white-space: pre-wrap;
  word-break: break-all;
  margin-top: var(--sv-s3);
  font-size: var(--sv-fs-caption1);
}
.none {
  text-align: center;
  padding: 40px 0;
}
</style>
