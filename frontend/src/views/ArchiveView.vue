<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import http, { type ApiResp } from '../api/http'

interface Summary {
  statWeek: string
  weekStart: string
  profile: { avgValence?: string; riskLevel: string; stressorTop?: { name: string; count: number }[]; distortionTop?: { name: string; count: number }[] }
  emotionPoints: { date: string; emotion: string; valence: string; intensity: string; sourceType: string }[]
  reports: { id: number; type: string; title: string; riskLevel: string; date: string }[]
  trainingScores: { date: string; avgScore: number }[]
}
interface Snapshot {
  nickname: string; generatedAt: string
  reports: { id: number; type: string; title: string; riskLevel: string; date: string; content: any }[]
  emotionPoints: Summary['emotionPoints']
  profiles: { statWeek: string; riskLevel: string; avgValence?: string; stressorTop?: any[]; distortionTop?: any[] }[]
}

const summary = ref<Summary | null>(null)
const snapshot = ref<Snapshot | null>(null)      // 领取后的导出快照（打印页）
const exporting = ref(false)
const error = ref('')

async function load() {
  const { data } = await http.get<ApiResp<Summary>>('/archive/summary')
  summary.value = data.data
}
onMounted(load)

const TYPE_LABELS: Record<string, string> = { TRACE: '溯源复盘', SIMULATE: '沟通复盘', SUPPORT: '自助方案' }

async function exportArchive() {
  if (exporting.value) return
  exporting.value = true
  error.value = ''
  try {
    const { data } = await http.post<ApiResp<{ fileId: string }>>('/archive/export', {})
    const dl = await http.get<ApiResp<Snapshot>>(`/archive/export/${data.data.fileId}`)
    snapshot.value = dl.data.data
  } catch (e: any) {
    error.value = e.message || '导出失败，链接限时 5 分钟且仅能领取一次'
  } finally {
    exporting.value = false
  }
}

const riskLabel = computed(() => ({ LOW: '平稳', MEDIUM: '需要关照', HIGH: '关怀中' } as Record<string, string>))

function doPrint() { window.print() }
</script>

<template>
  <div class="archive">
    <header class="no-print">
      <router-link to="/" class="back">← 首页</router-link>
      <b>成长档案</b>
      <button class="primary" :disabled="exporting" @click="exportArchive">
        {{ exporting ? '生成快照…' : '导出我的档案（打印页）' }}
      </button>
    </header>
    <main>
      <p v-if="error" class="err no-print">{{ error }}</p>

      <!-- 打印快照视图 -->
      <template v-if="snapshot">
        <div class="print-only title-page">
          <h1>{{ snapshot.nickname }} 的心屿成长档案</h1>
          <p>生成时间 {{ snapshot.generatedAt.slice(0, 16).replace('T', ' ') }} UTC · 由心屿漫行自助平台整理</p>
        </div>
        <section class="card">
          <h3>报告（{{ snapshot.reports.length }} 份）</h3>
          <div v-for="r in snapshot.reports" :key="r.id" class="snap-report">
            <b>{{ r.title }}</b>
            <span class="type">{{ TYPE_LABELS[r.type] || r.type }}</span>
            <small>{{ r.date }}</small>
            <template v-if="r.content && typeof r.content === 'object'">
              <p v-if="r.content.report?.insight" class="insight">💡 {{ r.content.report.insight }}</p>
              <p v-if="r.content.planTitle" class="insight">🌿 {{ r.content.planTitle }}</p>
              <p v-if="r.content.overall" class="insight">沟通分 {{ r.content.overall.avgScore }}</p>
            </template>
          </div>
        </section>
        <section class="card">
          <h3>周画像</h3>
          <table class="tbl">
            <thead><tr><th>周</th><th>平均效价</th><th>风险档</th><th>压力源 Top</th></tr></thead>
            <tbody>
              <tr v-for="w in snapshot.profiles" :key="w.statWeek">
                <td>{{ w.statWeek }}</td>
                <td>{{ w.avgValence ?? '—' }}</td>
                <td>{{ riskLabel[w.riskLevel] || w.riskLevel }}</td>
                <td>{{ (w.stressorTop || []).map((s: any) => `${s.name}×${s.count}`).join('、') || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </section>
        <section class="card">
          <h3>情绪记录（{{ snapshot.emotionPoints.length }} 点）</h3>
          <ul class="points">
            <li v-for="(p, i) in snapshot.emotionPoints" :key="i">
              {{ p.date }} · {{ p.emotion }}（效价 {{ p.valence }}，强度 {{ p.intensity }}）
            </li>
          </ul>
        </section>
        <p class="disclaimer">本档案为自助记录汇总，不构成任何医学诊断。<button class="ghost no-print" @click="doPrint">🖨 打印 / 存为 PDF</button></p>
      </template>

      <!-- 常规周报视图 -->
      <template v-else-if="summary">
        <section class="card">
          <div class="week-head">
            <h3>{{ summary.statWeek }}</h3>
            <span class="risk" :class="summary.profile.riskLevel.toLowerCase()">
              {{ riskLabel[summary.profile.riskLevel] || summary.profile.riskLevel }}
            </span>
            <span v-if="summary.profile.avgValence" class="avg">平均效价 {{ summary.profile.avgValence }}</span>
          </div>
          <p v-if="summary.profile.stressorTop?.length" class="line">
            压力源：<span v-for="s in summary.profile.stressorTop" :key="s.name" class="chip">{{ s.name }} ×{{ s.count }}</span>
          </p>
          <p v-if="summary.profile.distortionTop?.length" class="line">
            思维误区线索：<span v-for="d in summary.profile.distortionTop" :key="d.name" class="chip warm">{{ d.name }} ×{{ d.count }}</span>
          </p>
        </section>

        <section class="card">
          <h3>本周情绪点（{{ summary.emotionPoints.length }}）</h3>
          <div v-if="!summary.emotionPoints.length" class="sub">这周还没有记录。</div>
          <ul class="points">
            <li v-for="p in summary.emotionPoints" :key="p.date + p.emotion">
              {{ p.date }} · {{ p.emotion }}
              <small>{{ p.sourceType === 'DIARY' ? '日记' : p.sourceType }}</small>
            </li>
          </ul>
        </section>

        <section v-if="summary.trainingScores?.length" class="card">
          <h3>训练分数趋势</h3>
          <ul class="points">
            <li v-for="(t, i) in summary.trainingScores" :key="i">{{ t.date || '—' }} · 综合分 {{ t.avgScore }}</li>
          </ul>
        </section>

        <section class="card">
          <h3>本周报告</h3>
          <div v-if="!summary.reports?.length" class="sub">暂无。</div>
          <div v-for="r in summary.reports" :key="r.id" class="rep-row">
            <span class="rep">{{ r.title }}</span>
            <span class="type">{{ TYPE_LABELS[r.type] || r.type }}</span>
          </div>
        </section>
      </template>
      <div v-else class="sub">档案加载中…</div>
    </main>
  </div>
</template>

<style scoped>
.archive { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
header .primary { margin-left: auto; }
main { max-width: 720px; margin: 24px auto; padding: 0 16px; }
.card { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); margin-bottom: 16px; }
.card h3 { margin: 0 0 10px; font-size: 16px; color: #40466b; }
.err { color: #d4574e; } .sub { color: #8a93b5; font-size: 13px; }
.primary { padding: 9px 16px; background: #5b6cff; color: #fff; border: 0; border-radius: 10px; font-size: 14px; cursor: pointer; }
.primary:disabled { opacity: .55; }
.ghost { border: 1px solid #dde3f3; background: #fff; border-radius: 8px; padding: 4px 12px; cursor: pointer; font-size: 13px; }
.week-head { display: flex; align-items: center; gap: 10px; }
.risk { font-size: 12px; border-radius: 8px; padding: 1px 8px; }
.risk.low { background: #e2f6ea; color: #2f8a58; } .risk.medium { background: #fdf0dd; color: #b07714; } .risk.high { background: #fde5e2; color: #c04a3f; }
.avg { font-size: 12px; color: #8a93b5; margin-left: auto; }
.line { margin: 6px 0 0; font-size: 13px; color: #5c6483; }
.chip { background: #f0f3ff; border-radius: 6px; padding: 1px 8px; margin: 0 4px 2px 0; display: inline-block; color: #40508c; }
.chip.warm { background: #fdf6ea; color: #b07714; }
.points { margin: 0; padding-left: 18px; font-size: 13px; color: #40466b; line-height: 1.9; }
.points small { color: #9aa1bd; margin-left: 6px; }
.rep-row { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px dashed #eef1fa; font-size: 14px; }
.rep { color: #5b6cff; text-decoration: none; }
.type { color: #8a93b5; font-size: 12px; }
.snap-report { border: 1px solid #e8ecf8; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; font-size: 14px; color: #40466b; }
.snap-report small { color: #9aa1bd; margin-left: 8px; }
.insight { margin: 6px 0 0; font-size: 13px; color: #5c6483; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; color: #40466b; }
.tbl th { text-align: left; color: #8a93b5; font-weight: 500; border-bottom: 1px solid #eef1fa; padding: 6px 4px; }
.tbl td { padding: 6px 4px; border-bottom: 1px dashed #f2f5fc; }
.disclaimer { font-size: 12px; color: #9aa1bd; }
.title-page { display: none; }
.print-only { display: none; }

@media print {
  .no-print { display: none !important; }
  .print-only, .title-page { display: block; }
  .archive { background: #fff; }
  .card { box-shadow: none; border: 1px solid #eee; break-inside: avoid; }
  main { max-width: 100%; }
}
</style>
