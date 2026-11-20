<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart, RadarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvSegmented from '@/components/ui/SvSegmented.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import type { Json } from '@/api/http'
import { emotionApi } from '@/api/domains/emotion'
import { reportApi } from '@/api/domains/report'
import { isDark } from '@/composables/theme'
import { toast } from '@/stores/ui'
import { useCrisisStore } from '@/stores/crisis'
import { addBusinessDays, businessDate } from '@/utils/date'
import type { EmotionInsightView, EmotionPointView, WeekProfileView, WeeklyDigestView } from '@/types/domain'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  RadarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  MarkLineComponent,
  CanvasRenderer,
])
type Chart = ReturnType<typeof echarts.init>

const crisis = useCrisisStore()
const range = ref<number>(30)
const weeks = computed(() => crisis.weeks as WeekProfileView[])
const points = ref<EmotionPointView[]>([])
const empty = computed(() => points.value.length === 0)
const insight = ref<EmotionInsightView | null>(null)
const latestDigest = computed<WeeklyDigestView | null>(() => insight.value?.weekly[0] ?? null)
const digestByWeek = computed(() => new Map((insight.value?.weekly ?? []).map(w => [w.statWeek, w])))

const curveEl = ref<HTMLElement | null>(null)
const pieEl = ref<HTMLElement | null>(null)
const trendEl = ref<HTMLElement | null>(null)
const radarEl = ref<HTMLElement | null>(null)
const charts = shallowRef<Chart[]>([])

const DIM_LABELS: Record<string, string> = {
  LISTEN: '倾听',
  BOUNDARY: '边界表达',
  EMPATHY: '共情',
  CONCESSION: '让步',
}
const radarData = ref<{ name: string; value: number }[] | null>(null)

const today = () => businessDate()

async function load() {
  const from = businessDate(addBusinessDays(new Date(), -(range.value - 1)))
  const insightWeeks = Math.min(12, Math.max(1, Math.round(range.value / 7)))
  const [traj, ins] = await Promise.all([
    emotionApi.trajectory<{ points: EmotionPointView[] }>({ from, to: today() }),
    emotionApi.insight<EmotionInsightView>({ weeks: insightWeeks }).catch(() => null),
    crisis.refreshProfile().catch(() => {
      /* 画像失败仍可看曲线 */
    }),
  ])
  points.value = traj.points
  insight.value = ins
  try {
    const list = await reportApi.list<{ items: { id: number; type: string }[] }>({ page: 0, size: 20 })
    const sim = list.items.find(i => i.type === 'SIMULATE' || i.type === 'SIMULATE_REVIEW')
    if (sim) {
      const d = await reportApi.detail<{ content: Json }>(sim.id)
      const ts: Json[] = d.content?.turnScores ?? []
      const agg = new Map<string, number[]>()
      const G: Record<string, number> = { A: 4, B: 3, C: 2, D: 1 }
      for (const t of ts) {
        const k = DIM_LABELS[t.dimension] || t.dimension
        agg.set(k, [...(agg.get(k) || []), G[t.grade] ?? 2])
      }
      if (agg.size) {
        radarData.value = [...agg.entries()].map(([name, vs]) => ({
          name,
          value: +(vs.reduce((a, b) => a + b, 0) / vs.length).toFixed(1),
        }))
      }
    }
  } catch {
    /* 无模拟报告时不画雷达 */
  }
  await nextTick() // 等 v-if 卡片挂载后再取容器
  render()
}

const cssVar = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

function ensureChart(el: HTMLElement | null): Chart | undefined {
  if (!el) return undefined
  let c = echarts.getInstanceByDom(el)
  if (!c) c = echarts.init(el)
  return c
}

function baseOpt() {
  const label3 = cssVar('--sv-label3') || '#6f6659'
  const sep = cssVar('--sv-sep') || '#ddd3c2'
  return {
    textStyle: { color: label3, fontFamily: 'inherit', fontSize: 11 },
    axisLabel: label3,
    splitLine: sep,
  }
}

function tooltipOpt() {
  return {
    backgroundColor: cssVar('--sv-card'),
    borderColor: cssVar('--sv-sep'),
    borderWidth: 1,
    textStyle: { color: cssVar('--sv-label'), fontSize: 11, fontFamily: 'inherit' },
    extraCssText: 'box-shadow:none;border-radius:4px;',
  }
}

function render() {
  const b = baseOpt()
  const indigo = cssVar('--sv-indigo') || '#5B6CFF'
  charts.value = [curveEl.value, pieEl.value, trendEl.value, radarEl.value]
    .map(el => ensureChart(el))
    .filter(Boolean) as Chart[]

  // ① 情绪曲线：效价折线 + 强度柱
  const pts = points.value
  const dates = [...new Set(pts.map(p => p.date))]
  const avg = (key: 'valence' | 'intensity') =>
    dates.map(d => {
      const ps = pts.filter(p => p.date === d)
      return +(ps.reduce((s, p) => s + parseFloat(p[key]), 0) / ps.length).toFixed(2)
    })
  charts.value[0]?.setOption(
    {
      grid: { left: 36, right: 12, top: 34, bottom: 24 },
      tooltip: {
        ...tooltipOpt(),
        trigger: 'axis',
        formatter: (params: unknown) => {
          const d = (params as { axisValue: string }[])[0].axisValue
          const ps = pts.filter(p => p.date === d)
          return (
            d + '<br/>' + ps.map(p => `${p.emotion}（效价 ${p.valence} · 强度 ${p.intensity}）`).join('<br/>')
          )
        },
      },
      legend: { data: ['效价', '强度'], top: 0, textStyle: { color: b.axisLabel } },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: { color: b.axisLabel },
        axisLine: { lineStyle: { color: b.splitLine } },
      },
      yAxis: [
        {
          type: 'value',
          min: -1,
          max: 1,
          axisLabel: { color: b.axisLabel },
          splitLine: { lineStyle: { color: b.splitLine, type: 'dashed', width: 1 } },
        },
        { type: 'value', min: 0, max: 1, show: false },
      ],
      series: [
        {
          name: '效价',
          type: 'line',
          smooth: true,
          data: avg('valence'),
          lineStyle: { color: indigo, width: 1.5 },
          itemStyle: { color: indigo },
          symbolSize: 3,
          areaStyle: { color: 'color-mix(in srgb,' + indigo + ' 8%, transparent)' },
          connectNulls: true,
          markLine: {
            silent: true,
            symbol: 'none',
            label: { show: false },
            data: [{ yAxis: 0 }],
            lineStyle: { color: b.splitLine, width: 1 },
          },
        },
        {
          name: '强度',
          type: 'bar',
          yAxisIndex: 1,
          data: avg('intensity'),
          itemStyle: {
            color: cssVar('--sv-label3') || '#6f6659',
            borderRadius: 0,
          },
          barMaxWidth: 10,
        },
      ],
    },
    true,
  )

  // ② 压力源甜甜圈（周画像聚合）
  const stressor = new Map<string, number>()
  weeks.value.forEach(w =>
    w.stressorTop?.forEach(s => stressor.set(s.name, (stressor.get(s.name) || 0) + s.count)),
  )
  const top = [...stressor.entries()].sort((a, b2) => b2[1] - a[1]).slice(0, 6)
  charts.value[1]?.setOption(
    {
      color: [indigo, cssVar('--sv-label3'), cssVar('--sv-warn'), cssVar('--sv-ok')],
      tooltip: { ...tooltipOpt(), trigger: 'item' },
      legend: { bottom: 0, textStyle: { color: b.axisLabel, fontSize: 11 }, itemWidth: 10 },
      series: [
        {
          type: 'pie',
          radius: ['45%', '70%'],
          center: ['50%', '42%'],
          itemStyle: { borderWidth: 2, borderColor: cssVar('--sv-card') || '#fff' },
          label: { show: false },
          data: top.map(([name, value]) => ({ name, value })),
        },
      ],
    },
    true,
  )

  // ③ 思维误区线索趋势（按周合计）
  const ws = [...weeks.value].reverse()
  charts.value[2]?.setOption(
    {
      grid: { left: 30, right: 12, top: 18, bottom: 24 },
      tooltip: { ...tooltipOpt(), trigger: 'axis' },
      xAxis: { type: 'category', data: ws.map(w => w.statWeek.slice(5)), axisLabel: { color: b.axisLabel } },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: b.axisLabel },
        splitLine: { lineStyle: { color: b.splitLine, type: 'dashed', width: 1 } },
      },
      series: [
        {
          name: '线索次数',
          type: 'line',
          smooth: true,
          connectNulls: true,
          symbolSize: 3,
          data: ws.map(w => (w.distortionTop || []).reduce((s, d) => s + d.count, 0)),
          lineStyle: { color: indigo, width: 1.5 },
          itemStyle: { color: indigo },
        },
      ],
    },
    true,
  )

  // ④ 沟通四维雷达（最新一次模拟复盘）
  charts.value[3]?.setOption(
    {
      tooltip: { ...tooltipOpt() },
      radar: {
        indicator: (radarData.value || []).map(d => ({ name: d.name, max: 4 })),
        radius: '65%',
        axisName: { color: b.axisLabel, fontSize: 11 },
        splitLine: { lineStyle: { color: b.splitLine, width: 1 } },
        splitArea: { show: false },
        axisLine: { lineStyle: { color: b.splitLine, width: 1 } },
      },
      series: [
        {
          type: 'radar',
          data: [{ value: (radarData.value || []).map(d => d.value), name: 'NVO 沟通分' }],
          areaStyle: { color: 'color-mix(in srgb,' + indigo + ' 8%, transparent)' },
          lineStyle: { color: indigo, width: 1.5 },
          itemStyle: { color: indigo },
          symbolSize: 3,
        },
      ],
    },
    true,
  )
}

function onResize() {
  charts.value.forEach(c => c.resize())
}

async function onFoldToggle() {
  await nextTick()
  render()
}
onMounted(async () => {
  await load().catch(() => toast('洞察加载失败'))
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  charts.value.forEach(c => c.dispose())
})
watch(isDark, () => render())

const WEEK_RISK: Record<string, string> = { LOW: '平稳', MEDIUM: '需要关照', HIGH: '已在关怀中' }
const rangeOptions = [
  { label: '14 天', value: 14 },
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
]
watch(range, () => load())
</script>

<template>
  <div class="insights">
    <SvNavBar title="洞察">
      <template #actions>
        <SvSegmented
          class="range-seg"
          :model-value="range"
          :options="rangeOptions"
          @update:model-value="range = Number($event)"
        />
      </template>
    </SvNavBar>

    <SvPageBody>
      <CrisisReferral
        v-if="crisis.crisisMode"
        level="HIGH"
        headline="平台已进入关怀模式：常规生成暂停，支持资源优先呈现。"
      />

      <SvCard v-if="latestDigest">
        <div class="digest-head">
          <h2 class="t">本周小结</h2>
          <span class="sv-cap">{{ latestDigest.statWeek }}</span>
        </div>
        <p class="digest-title">{{ latestDigest.headline }}</p>
        <ul class="digest-lines">
          <li v-for="(line, i) in latestDigest.lines" :key="i">{{ line }}</li>
        </ul>
      </SvCard>

      <SvCard v-if="insight">
        <div class="vola-head">
          <h2 class="t">波动提示</h2>
          <span class="vola-tag" :class="insight.volatility.level.toLowerCase()">{{
            insight.volatility.label
          }}</span>
        </div>
        <p class="sv-muted">{{ insight.volatility.hint }}</p>
        <div v-if="insight.volatility.sampleDays" class="vola-metrics">
          <span>样本 {{ insight.volatility.sampleDays }} 天</span>
          <span v-if="insight.volatility.stdDev != null"
            >标准差 {{ Number(insight.volatility.stdDev).toFixed(2) }}</span
          >
          <span v-if="insight.volatility.maxDailyDelta != null"
            >最大单日变化 {{ Number(insight.volatility.maxDailyDelta).toFixed(2) }}</span
          >
        </div>
      </SvCard>

      <SvCard>
        <h2 class="t">情绪曲线</h2>
        <p class="sv-muted">效价（-1 低落 ~ +1 明亮）与强度随日期的走势，数据来自你的每一次记录。</p>
        <div
          ref="curveEl"
          class="chart"
          :style="{ display: empty ? 'none' : 'block' }"
          role="img"
          aria-label="情绪效价与强度折线图"
        />
        <p v-if="empty" class="sv-muted none">还没有足够记录——去写一篇情绪日记或打个卡，曲线会自己长出来。</p>
      </SvCard>

      <SvCard v-if="weeks.length">
        <h2 class="t">压力源构成</h2>
        <div ref="pieEl" class="chart sm" role="img" aria-label="压力来源占比环形图" />
        <p v-if="!points.length" class="sv-cap none">暂无压力源统计</p>
      </SvCard>

      <SvCard v-if="weeks.some(w => w.distortionTop?.length)">
        <details class="fold" @toggle="onFoldToggle">
          <summary>思维误区线索趋势</summary>
          <p class="sv-muted">
            每周溯源中被识别到的认知误区线索次数——起伏本身不是坏消息，看见它就是改变的开始。
          </p>
          <div ref="trendEl" class="chart sm" role="img" aria-label="思维误区线索按周趋势线图" />
        </details>
      </SvCard>

      <SvCard v-if="radarData">
        <details class="fold" @toggle="onFoldToggle">
          <summary>沟通四维 · 最近一次模拟</summary>
          <div ref="radarEl" class="chart" role="img" aria-label="倾听、边界表达、共情、让步四维雷达图" />
        </details>
      </SvCard>

      <SvCard>
        <h2 class="t">周画像</h2>
        <p v-if="!weeks.length" class="sv-muted">暂无周画像，完成一次日记梳理后自动生成。</p>
        <div v-for="w in weeks" :key="w.statWeek" class="week">
          <div class="week-head">
            <b>{{ w.statWeek }}</b>
            <span class="risk" :class="w.riskLevel.toLowerCase()">{{
              WEEK_RISK[w.riskLevel] || w.riskLevel
            }}</span>
            <span v-if="w.avgValence !== '' && w.avgValence != null" class="sv-cap"
              >平均效价 {{ Number(w.avgValence).toFixed(2) }}</span
            >
          </div>
          <p v-if="digestByWeek.get(w.statWeek)" class="sv-muted week-digest">
            {{ digestByWeek.get(w.statWeek)?.headline }}
          </p>
          <p v-if="w.stressorTop?.length" class="line">
            压力源：<span v-for="s in w.stressorTop" :key="s.name" class="tag"
              >{{ s.name }} ×{{ s.count }}</span
            >
          </p>
          <p v-if="w.distortionTop?.length" class="line">
            误区线索：<span v-for="d in w.distortionTop" :key="d.name" class="tag warm"
              >{{ d.name }} ×{{ d.count }}</span
            >
          </p>
        </div>
      </SvCard>

      <SvCard v-if="insight && (insight.stressors.length || insight.distortions.length)">
        <h2 class="t">看见这些线索</h2>
        <div v-for="s in insight.stressors" :key="'s-' + s.name" class="explain">
          <div class="explain-head">
            <b>压力源 · {{ s.name }}</b>
            <span class="sv-cap">×{{ s.count }}</span>
          </div>
          <p class="sv-muted">{{ s.what }}</p>
          <p class="sv-cap tip">可以试试：{{ s.tip }}</p>
        </div>
        <div v-for="d in insight.distortions" :key="'d-' + d.name" class="explain">
          <div class="explain-head">
            <b>误区线索 · {{ d.name }}</b>
            <span class="sv-cap">×{{ d.count }}</span>
          </div>
          <p v-if="d.definition" class="sv-muted">{{ d.definition }}</p>
          <p v-if="d.typicalSignature" class="sv-cap">常见句式：{{ d.typicalSignature }}</p>
          <p v-if="d.question" class="sv-cap tip">可以问问自己：{{ d.question }}</p>
        </div>
      </SvCard>

      <SvCard v-if="insight?.actions.length">
        <h2 class="t">下一步</h2>
        <router-link v-for="a in insight.actions" :key="a.code" :to="a.route" class="action">
          <span class="action-title">{{ a.title }}</span>
          <span class="sv-cap">{{ a.detail }}</span>
          <span class="action-go">→</span>
        </router-link>
      </SvCard>

      <SvDisclaimer
        text="趋势仅供参考，不构成任何诊断。如需专业帮助请拨打 12356。"
        reason="所有数据点来自你自己的日记、模拟与打卡记录（含 SELF_RATING），没有外部数据。"
      />
    </SvPageBody>
  </div>
</template>

<style scoped>
.digest-head,
.vola-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.digest-title {
  margin: 2px 0 8px;
  font-size: var(--sv-fs-title3);
  color: var(--sv-label);
}
.digest-lines {
  margin: 0;
  padding-left: 18px;
  color: var(--sv-label2);
  font-size: var(--sv-fs-body);
  line-height: 1.7;
}
.vola-tag {
  font-size: var(--sv-fs-caption1);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  color: var(--sv-label2);
  white-space: nowrap;
}
.vola-tag.wavy {
  color: var(--sv-warn);
  border-color: var(--sv-warn);
}
.vola-tag.volatile {
  color: var(--sv-danger);
  border-color: var(--sv-danger);
}
.vola-tag.insufficient {
  color: var(--sv-label3);
}
.vola-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 6px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label3);
}
.explain {
  border-top: 1px solid var(--sv-sep);
  padding-top: 10px;
  margin-top: 10px;
}
.explain:first-of-type {
  border-top: none;
  padding-top: 0;
  margin-top: 0;
}
.explain-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.explain p {
  margin: 4px 0 0;
}
.tip {
  color: var(--sv-indigo);
}
.week-digest {
  margin: 2px 0 0;
}
.action {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 20px 10px 0;
  border-top: 1px solid var(--sv-sep);
  color: inherit;
  text-decoration: none;
}
.action:first-of-type {
  border-top: none;
}
.action-title {
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
}
.action-go {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  color: var(--sv-label3);
}
.range-seg {
  min-width: 170px;
}
.t {
  font-size: var(--sv-fs-title3);
  margin-bottom: var(--sv-s2);
}
.chart {
  width: 100%;
  height: 260px;
  margin-top: var(--sv-s2);
}
.chart.sm {
  height: 220px;
}
.none {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.week {
  border-top: 1px solid var(--sv-sep);
  padding: 12px 0;
  font-size: var(--sv-fs-body);
}
.week:first-of-type {
  border-top: none;
}
.week-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.risk {
  font-size: var(--sv-fs-caption1);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
}
.risk.low {
  color: var(--sv-ok);
  border-color: var(--sv-ok);
}
.risk.medium {
  color: var(--sv-warn);
  border-color: var(--sv-warn);
}
.risk.high {
  color: var(--sv-danger);
  border-color: var(--sv-danger);
}
.line {
  margin: 6px 0 0;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label2);
}
.tag {
  display: inline-block;
  margin: 0 4px 2px 0;
  font-size: var(--sv-fs-caption1);
  border: 1px solid var(--sv-sep);
  color: var(--sv-label2);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
}
.warm {
  color: var(--sv-warn);
  border-color: var(--sv-warn);
}
.fold summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  list-style: none;
  font-size: var(--sv-fs-title3);
  color: var(--sv-label);
  padding-bottom: var(--sv-s2);
}
.fold summary::-webkit-details-marker {
  display: none;
}
.fold[open] summary {
  border-bottom: 1px solid var(--sv-sep);
}
.fold .chart {
  margin-top: var(--sv-s3);
}
</style>
