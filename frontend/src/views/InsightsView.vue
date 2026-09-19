<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import http, { type ApiResp } from '../api/http'
import CrisisCard from '../components/CrisisCard.vue'

interface Point { date: string; emotion: string; valence: string; intensity: string; sourceType: string }
interface Week {
  statWeek: string; avgValence: string | number; riskLevel: string
  stressorTop: { name: string; count: number }[]
  distortionTop: { name: string; count: number }[]
}

const chartEl = ref<HTMLElement | null>(null)
const chart = shallowRef<echarts.ECharts>()
const weeks = ref<Week[]>([])
const crisisMode = ref(false)
const range = ref(30)
const empty = ref(false)

async function load() {
  const to = new Date()
  const from = new Date(to.getTime() - (range.value - 1) * 86400000)
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  const [traj, prof] = await Promise.all([
    http.get<ApiResp<{ points: Point[] }>>('/emotions/trajectory', {
      params: { from: fmt(from), to: fmt(to) },
    }),
    http.get<ApiResp<{ crisisMode: boolean; weeks: Week[] }>>('/emotions/profile'),
  ])
  crisisMode.value = prof.data.data.crisisMode
  weeks.value = prof.data.data.weeks
  render(traj.data.data.points)
}

function render(points: Point[]) {
  empty.value = points.length === 0
  if (!chartEl.value) return
  chart.value ??= echarts.init(chartEl.value)
  const dates = [...new Set(points.map((p) => p.date))]
  // 同一天多个来源取均值
  const avg = (key: 'valence' | 'intensity') => dates.map((d) => {
    const ps = points.filter((p) => p.date === d)
    return +(ps.reduce((s, p) => s + parseFloat(p[key]), 0) / ps.length).toFixed(2)
  })
  chart.value.setOption({
    grid: { left: 40, right: 16, top: 34, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const d = params[0].axisValue
        const ps = points.filter((p) => p.date === d)
        return d + '<br/>' + ps.map((p) => `${p.emotion}（效价 ${p.valence} · 强度 ${p.intensity}）`).join('<br/>')
      },
    },
    legend: { data: ['情绪效价', '强度'], top: 0, textStyle: { color: '#5c6483' } },
    xAxis: { type: 'category', data: dates, axisLabel: { color: '#8a93b5' } },
    yAxis: [
      { type: 'value', min: -1, max: 1, axisLabel: { color: '#8a93b5' }, splitLine: { lineStyle: { color: '#eef1fa' } } },
      { type: 'value', min: 0, max: 1, show: false },
    ],
    series: [
      {
        name: '情绪效价', type: 'line', smooth: true, data: avg('valence'),
        lineStyle: { color: '#5b6cff' }, itemStyle: { color: '#5b6cff' },
        areaStyle: { color: 'rgba(91,108,255,.08)' }, connectNulls: true,
        markLine: { silent: true, symbol: 'none', label: { show: false }, data: [{ yAxis: 0 }], lineStyle: { color: '#d8def2' } },
      },
      {
        name: '强度', type: 'bar', yAxisIndex: 1, data: avg('intensity'),
        itemStyle: { color: 'rgba(240,163,94,.55)' }, barMaxWidth: 10,
      },
    ],
  })
}

onMounted(async () => {
  await load()
  window.addEventListener('resize', () => chart.value?.resize())
})

const WEEK_RISK: Record<string, string> = { LOW: '平稳', MEDIUM: '需要关照', HIGH: '已在关怀中' }
</script>

<template>
  <div class="insights">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>情绪洞察</b>
      <select v-model.number="range" class="range" @change="load">
        <option :value="14">近 14 天</option>
        <option :value="30">近 30 天</option>
        <option :value="90">近 90 天</option>
      </select>
    </header>
    <main>
      <CrisisCard v-if="crisisMode" level="HIGH" headline="平台已进入关怀模式：常规生成暂停，支持资源优先呈现。" />

      <section class="card">
        <h3>情绪曲线</h3>
        <p class="sub">效价（-1 低落 ~ +1 明亮）与强度随日期的走势，数据来自你的每一次记录。</p>
        <div ref="chartEl" class="chart" :style="{ display: empty ? 'none' : 'block' }" />
        <p v-if="empty" class="sub">还没有足够记录——去写一篇情绪日记，曲线会自己长出来。</p>
      </section>

      <section class="card">
        <h3>周画像</h3>
        <div v-if="!weeks.length" class="sub">暂无周画像，完成一次日记梳理后自动生成。</div>
        <div v-for="w in weeks" :key="w.statWeek" class="week">
          <div class="week-head">
            <b>{{ w.statWeek }}</b>
            <span class="risk" :class="w.riskLevel.toLowerCase()">{{ WEEK_RISK[w.riskLevel] || w.riskLevel }}</span>
            <span v-if="w.avgValence !== '' && w.avgValence != null" class="avg">平均效价 {{ Number(w.avgValence).toFixed(2) }}</span>
          </div>
          <p v-if="w.stressorTop?.length" class="line">
            压力源：<span v-for="s in w.stressorTop" :key="s.name" class="chip">{{ s.name }} ×{{ s.count }}</span>
          </p>
          <p v-if="w.distortionTop?.length" class="line">
            思维误区线索：<span v-for="d in w.distortionTop" :key="d.name" class="chip warm">{{ d.name }} ×{{ d.count }}</span>
          </p>
        </div>
      </section>
      <p class="disclaimer">趋势仅供参考，不构成任何诊断。如需专业帮助请拨打 12356。</p>
    </main>
  </div>
</template>

<style scoped>
.insights { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
.range { margin-left: auto; border: 1px solid #dde3f3; border-radius: 8px; padding: 4px 8px; color: #5c6483; background: #fff; }
main { max-width: 760px; margin: 24px auto; padding: 0 16px; }
.card { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); margin-bottom: 16px; }
.card h3 { margin: 0 0 6px; font-size: 16px; color: #40466b; }
.sub { color: #8a93b5; font-size: 13px; margin: 0 0 10px; }
.chart { width: 100%; height: 300px; }
.week { border-top: 1px dashed #e8ecf8; padding: 10px 0; font-size: 14px; color: #40466b; }
.week-head { display: flex; align-items: center; gap: 10px; }
.risk { font-size: 12px; border-radius: 8px; padding: 1px 8px; }
.risk.low { background: #e2f6ea; color: #2f8a58; }
.risk.medium { background: #fdf0dd; color: #b07714; }
.risk.high { background: #fde5e2; color: #c04a3f; }
.avg { font-size: 12px; color: #8a93b5; margin-left: auto; }
.line { margin: 6px 0 0; font-size: 13px; color: #5c6483; }
.chip { background: #f0f3ff; border-radius: 6px; padding: 1px 8px; margin: 0 4px 2px 0; display: inline-block; color: #40508c; }
.chip.warm { background: #fdf6ea; color: #b07714; }
.disclaimer { text-align: center; color: #9aa1bd; font-size: 12px; margin-top: 20px; }
</style>
