<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart, RadarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvSegmented from '@/components/ui/SvSegmented.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import http, { type ApiResp } from '@/api/http'
import { isDark } from '@/composables/theme'
import { toast } from '@/stores/ui'
import { useCrisisStore } from '@/stores/crisis'

echarts.use([LineChart, BarChart, PieChart, RadarChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer])
type Chart = ReturnType<typeof echarts.init>

interface Point { date: string; emotion: string; valence: string; intensity: string; sourceType: string }
interface Week {
  statWeek: string; avgValence: string | number; riskLevel: string
  stressorTop: { name: string; count: number }[]
  distortionTop: { name: string; count: number }[]
}

const crisis = useCrisisStore()
const range = ref<number>(30)
const weeks = computed(() => crisis.weeks as Week[])
const points = ref<Point[]>([])
const empty = computed(() => points.value.length === 0)

const curveEl = ref<HTMLElement | null>(null)
const pieEl = ref<HTMLElement | null>(null)
const trendEl = ref<HTMLElement | null>(null)
const radarEl = ref<HTMLElement | null>(null)
const charts = shallowRef<Chart[]>([])

const DIM_LABELS: Record<string, string> = { LISTEN: '倾听', BOUNDARY: '边界表达', EMPATHY: '共情', CONCESSION: '让步' }
const radarData = ref<{ name: string; value: number }[] | null>(null)

const today = () => new Date().toLocaleDateString('en-CA')
function fmt(d: Date) { return d.toLocaleDateString('en-CA') }

async function load() {
  const from = fmt(new Date(Date.now() - (range.value - 1) * 86400000))
  const [traj] = await Promise.all([
    http.get<ApiResp<{ points: Point[] }>>('/emotions/trajectory', { params: { from, to: today() } }),
    crisis.refreshProfile().catch(() => { /* 画像失败仍可看曲线 */ }),
  ])
  points.value = traj.data.data.points
  try {
    const { data } = await http.get<ApiResp<{ items: { id: number; type: string }[] }>>('/reports', {
      params: { page: 0, size: 20 },
    })
    const sim = data.data.items.find((i) => i.type === 'SIMULATE' || i.type === 'SIMULATE_REVIEW')
    if (sim) {
      const d = await http.get<ApiResp<{ content: any }>>(`/reports/${sim.id}`)
      const ts: any[] = d.data.data.content?.turnScores ?? []
      const agg = new Map<string, number[]>()
      const G: Record<string, number> = { A: 4, B: 3, C: 2, D: 1 }
      for (const t of ts) {
        const k = DIM_LABELS[t.dimension] || t.dimension
        agg.set(k, [...(agg.get(k) || []), G[t.grade] ?? 2])
      }
      if (agg.size) {
        radarData.value = [...agg.entries()].map(([name, vs]) => ({
          name, value: +(vs.reduce((a, b) => a + b, 0) / vs.length).toFixed(1),
        }))
      }
    }
  } catch { /* 无模拟报告时不画雷达 */ }
  await nextTick()   // 等 v-if 卡片挂载后再取容器
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
  const label2 = cssVar('--sv-label2') || '#8a93b5'
  const sep = cssVar('--sv-sep') || '#eef1fa'
  return {
    textStyle: { color: label2, fontFamily: 'inherit' },
    axisLabel: label2, splitLine: sep,
  }
}

function render() {
  const b = baseOpt()
  const indigo = cssVar('--sv-indigo') || '#5B6CFF'
  charts.value = [curveEl.value, pieEl.value, trendEl.value, radarEl.value]
    .map(el => ensureChart(el)).filter(Boolean) as Chart[]

  // ① 情绪曲线：效价折线 + 强度柱
  const pts = points.value
  const dates = [...new Set(pts.map((p) => p.date))]
  const avg = (key: 'valence' | 'intensity') => dates.map((d) => {
    const ps = pts.filter((p) => p.date === d)
    return +(ps.reduce((s, p) => s + parseFloat(p[key]), 0) / ps.length).toFixed(2)
  })
  charts.value[0]?.setOption({
    grid: { left: 36, right: 12, top: 34, bottom: 24 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const d = params[0].axisValue
        const ps = pts.filter((p) => p.date === d)
        return d + '<br/>' + ps.map((p) => `${p.emotion}（效价 ${p.valence} · 强度 ${p.intensity}）`).join('<br/>')
      },
    },
    legend: { data: ['效价', '强度'], top: 0, textStyle: { color: b.axisLabel } },
    xAxis: { type: 'category', data: dates, axisLabel: { color: b.axisLabel }, axisLine: { lineStyle: { color: b.splitLine } } },
    yAxis: [
      { type: 'value', min: -1, max: 1, axisLabel: { color: b.axisLabel }, splitLine: { lineStyle: { color: b.splitLine } } },
      { type: 'value', min: 0, max: 1, show: false },
    ],
    series: [
      {
        name: '效价', type: 'line', smooth: true, data: avg('valence'),
        lineStyle: { color: indigo }, itemStyle: { color: indigo },
        areaStyle: { color: 'color-mix(in srgb,' + indigo + ' 10%, transparent)' }, connectNulls: true,
        markLine: { silent: true, symbol: 'none', label: { show: false }, data: [{ yAxis: 0 }], lineStyle: { color: b.splitLine } },
      },
      { name: '强度', type: 'bar', yAxisIndex: 1, data: avg('intensity'), itemStyle: { color: 'color-mix(in srgb,' + (cssVar('--sv-amber') || '#f0a35e') + ' 55%, transparent)' }, barMaxWidth: 10 },
    ],
  }, true)

  // ② 压力源甜甜圈（周画像聚合）
  const stressor = new Map<string, number>()
  weeks.value.forEach((w) => w.stressorTop?.forEach((s) => stressor.set(s.name, (stressor.get(s.name) || 0) + s.count)))
  const top = [...stressor.entries()].sort((a, b2) => b2[1] - a[1]).slice(0, 6)
  charts.value[1]?.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: b.axisLabel }, itemWidth: 10 },
    series: [{
      type: 'pie', radius: ['45%', '70%'], center: ['50%', '42%'],
      itemStyle: { borderWidth: 2, borderColor: cssVar('--sv-card') || '#fff' },
      label: { show: false },
      data: top.map(([name, value]) => ({ name, value })),
    }],
  }, true)

  // ③ 思维误区线索趋势（按周合计）
  const ws = [...weeks.value].reverse()
  charts.value[2]?.setOption({
    grid: { left: 30, right: 12, top: 18, bottom: 24 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: ws.map((w) => w.statWeek.slice(5)), axisLabel: { color: b.axisLabel } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: b.axisLabel }, splitLine: { lineStyle: { color: b.splitLine } } },
    series: [{
      name: '线索次数', type: 'line', smooth: true, connectNulls: true,
      data: ws.map((w) => (w.distortionTop || []).reduce((s, d) => s + d.count, 0)),
      lineStyle: { color: cssVar('--sv-amber') || '#f0a35e' }, itemStyle: { color: cssVar('--sv-amber') || '#f0a35e' },
    }],
  }, true)

  // ④ 沟通四维雷达（最新一次模拟复盘）
  charts.value[3]?.setOption({
    tooltip: {},
    radar: {
      indicator: (radarData.value || []).map((d) => ({ name: d.name, max: 4 })),
      radius: '65%',
      axisName: { color: b.axisLabel, fontSize: 11 },
      splitLine: { lineStyle: { color: b.splitLine } },
      splitArea: { show: false },
      axisLine: { lineStyle: { color: b.splitLine } },
    },
    series: [{
      type: 'radar',
      data: [{ value: (radarData.value || []).map((d) => d.value), name: 'NVO 沟通分' }],
      areaStyle: { color: 'color-mix(in srgb,' + indigo + ' 22%, transparent)' },
      lineStyle: { color: indigo }, itemStyle: { color: indigo },
    }],
  }, true)
}

function onResize() { charts.value.forEach((c) => c.resize()) }
onMounted(async () => {
  await load().catch(() => toast('洞察加载失败'))
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  charts.value.forEach((c) => c.dispose())
})
watch(isDark, () => render())

const WEEK_RISK: Record<string, string> = { LOW: '平稳', MEDIUM: '需要关照', HIGH: '已在关怀中' }
const rangeOptions = [{ label: '14 天', value: 14 }, { label: '30 天', value: 30 }, { label: '90 天', value: 90 }]
watch(range, () => load())
</script>

<template>
  <div class="insights">
    <SvNavBar title="洞察">
      <template #actions>
        <SvSegmented class="range-seg" :model-value="range" :options="rangeOptions" @update:model-value="range = Number($event)" />
      </template>
    </SvNavBar>

    <div class="body">
      <CrisisReferral v-if="crisis.crisisMode" level="HIGH"
        headline="平台已进入关怀模式：常规生成暂停，支持资源优先呈现。" />

      <SvCard>
        <h2 class="t">情绪曲线</h2>
        <p class="sv-muted">效价（-1 低落 ~ +1 明亮）与强度随日期的走势，数据来自你的每一次记录。</p>
        <div ref="curveEl" class="chart" :style="{ display: empty ? 'none' : 'block' }" role="img"
          aria-label="情绪效价与强度折线图" />
        <p v-if="empty" class="sv-muted none">还没有足够记录——去写一篇情绪日记或打个卡，曲线会自己长出来。</p>
      </SvCard>

      <SvCard v-if="weeks.length">
        <h2 class="t">压力源构成</h2>
        <div ref="pieEl" class="chart sm" role="img" aria-label="压力来源占比环形图" />
        <p v-if="!points.length" class="sv-cap none">暂无压力源统计</p>
      </SvCard>

      <SvCard v-if="weeks.some(w => w.distortionTop?.length)">
        <h2 class="t">思维误区线索趋势</h2>
        <p class="sv-muted">每周溯源中被识别到的认知误区线索次数——起伏本身不是坏消息，看见它就是改变的开始。</p>
        <div ref="trendEl" class="chart sm" role="img" aria-label="思维误区线索按周趋势线图" />
      </SvCard>

      <SvCard v-if="radarData">
        <h2 class="t">沟通四维 · 最近一次模拟</h2>
        <div ref="radarEl" class="chart" role="img" aria-label="倾听、边界表达、共情、让步四维雷达图" />
      </SvCard>

      <SvCard>
        <h2 class="t">周画像</h2>
        <p v-if="!weeks.length" class="sv-muted">暂无周画像，完成一次日记梳理后自动生成。</p>
        <div v-for="w in weeks" :key="w.statWeek" class="week">
          <div class="week-head">
            <b>{{ w.statWeek }}</b>
            <span class="risk" :class="w.riskLevel.toLowerCase()">{{ WEEK_RISK[w.riskLevel] || w.riskLevel }}</span>
            <span v-if="w.avgValence !== '' && w.avgValence != null" class="sv-cap">平均效价 {{ Number(w.avgValence).toFixed(2) }}</span>
          </div>
          <p v-if="w.stressorTop?.length" class="line">
            压力源：<span v-for="s in w.stressorTop" :key="s.name" class="tag">{{ s.name }} ×{{ s.count }}</span>
          </p>
          <p v-if="w.distortionTop?.length" class="line">
            误区线索：<span v-for="d in w.distortionTop" :key="d.name" class="tag warm">{{ d.name }} ×{{ d.count }}</span>
          </p>
        </div>
      </SvCard>

      <SvDisclaimer text="趋势仅供参考，不构成任何诊断。如需专业帮助请拨打 12356。"
        reason="所有数据点来自你自己的日记、模拟与打卡记录（含 SELF_RATING），没有外部数据。" />
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.range-seg { min-width: 170px; }
.t { font-size: var(--sv-fs-title3); margin-bottom: var(--sv-s2); }
.chart { width: 100%; height: 260px; margin-top: var(--sv-s2); }
.chart.sm { height: 220px; }
.none { text-align: center; padding: var(--sv-s4) 0; }
.week { border-top: 1px dashed var(--sv-sep); padding: 10px 0; font-size: var(--sv-fs-subhead); }
.week:first-of-type { border-top: none; }
.week-head { display: flex; align-items: center; gap: 10px; }
.risk { font-size: var(--sv-fs-caption1); border-radius: var(--sv-r-pill); padding: 1px 8px; }
.risk.low { background: color-mix(in srgb, var(--sv-mint) 14%, var(--sv-card)); color: var(--sv-mint); }
.risk.medium { background: color-mix(in srgb, var(--sv-amber) 16%, var(--sv-card)); color: var(--sv-amber); }
.risk.high { background: color-mix(in srgb, var(--sv-red) 14%, var(--sv-card)); color: var(--sv-red); }
.line { margin: 6px 0 0; font-size: var(--sv-fs-caption1); color: var(--sv-label2); }
.tag { display: inline-block; margin: 0 4px 2px 0; font-size: var(--sv-fs-caption1);
  background: var(--sv-fill2); color: var(--sv-label2); border-radius: var(--sv-r-pill); padding: 2px 8px; }
.warm { background: color-mix(in srgb, var(--sv-amber) 14%, transparent); color: var(--sv-amber); }
</style>
