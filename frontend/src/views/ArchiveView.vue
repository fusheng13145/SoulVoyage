<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import http, { type ApiResp } from '@/api/http'
import { confirmDialog, toast } from '@/stores/ui'

interface Summary {
  statWeek: string
  weekStart: string
  profile: { avgValence?: string; riskLevel: string; stressorTop?: { name: string; count: number }[]; distortionTop?: { name: string; count: number }[] }
  emotionPoints: { date: string; emotion: string; valence: string; intensity: string; sourceType: string }[]
  reports: { id: number; type: string; title: string; riskLevel: string; date: string }[]
  trainingScores: { date: string; avgScore: number }[]
}
interface ReportMeta { id: number; type: string; title: string; riskLevel: string; createdAt: string }
interface Snapshot {
  nickname: string; generatedAt: string
  reports: { id: number; type: string; title: string; riskLevel: string; date: string; content: any }[]
  emotionPoints: Summary['emotionPoints']
  profiles: { statWeek: string; riskLevel: string; avgValence?: string; stressorTop?: any[]; distortionTop?: any[] }[]
}

const router = useRouter()
const summary = ref<Summary | null>(null)
const allReports = ref<ReportMeta[]>([])
const repTotal = ref(0)
const repPage = ref(0)
const exporting = ref(false)
const snapshot = ref<Snapshot | null>(null)      // 领取后的导出快照（打印页）
const loading = ref(true)

const TYPE_LABELS: Record<string, string> = {
  TRACE: '溯源复盘', DIARY_TRACE: '溯源复盘', DIARY_REPORT: '复盘报告',
  SIMULATE: '沟通复盘', SIMULATE_REVIEW: '沟通复盘', SUPPORT: '自助方案', WEEKLY: '周报',
}
const riskLabel = computed(() => ({ LOW: '平稳', MEDIUM: '需要关照', HIGH: '关怀中' } as Record<string, string>))
const riskTone = (r: string) => ({ HIGH: 'var(--sv-red)', MEDIUM: 'var(--sv-amber)' }[r] || 'var(--sv-mint)')

async function load() {
  loading.value = true
  try {
    const [s, r] = await Promise.all([
      http.get<ApiResp<Summary>>('/archive/summary'),
      http.get<ApiResp<{ items: ReportMeta[]; total: number }>>('/reports', { params: { page: 0, size: 10 } }),
    ])
    summary.value = s.data.data
    allReports.value = r.data.data.items
    repTotal.value = r.data.data.total
  } catch (e: any) {
    toast(e.message || '档案加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function moreReports() {
  repPage.value += 1
  const { data } = await http.get<ApiResp<{ items: ReportMeta[] }>>('/reports', {
    params: { page: repPage.value, size: 10 },
  })
  allReports.value = [...allReports.value, ...data.data.items]
}

/** 导出：POST 领限时 fileId → GET 一次性领取快照 → 打印视图 */
async function exportArchive() {
  if (exporting.value) return
  const ok = await confirmDialog({
    title: '导出成长档案？',
    message: '生成一份含全部报告、周画像与情绪记录的打印快照。链接限时 5 分钟、仅能领取一次。',
    confirmText: '生成快照',
  })
  if (!ok) return
  exporting.value = true
  try {
    const { data } = await http.post<ApiResp<{ fileId: string }>>('/archive/export', {})
    const dl = await http.get<ApiResp<Snapshot>>(`/archive/export/${data.data.fileId}`)
    snapshot.value = dl.data.data
  } catch (e: any) {
    toast(e.message || '导出失败，链接限时 5 分钟且仅能领取一次')
  } finally {
    exporting.value = false
  }
}

function doPrint() { window.print() }
</script>

<template>
  <div class="archive">
    <SvNavBar title="成长档案" back="返回" :large="false">
      <template #actions>
        <button class="ic" :disabled="exporting" aria-label="导出我的档案" @click="exportArchive">
          <SvIcon :name="exporting ? 'i-refresh' : 'i-export'" :size="20" tone="inherit" />
        </button>
      </template>
    </SvNavBar>

    <div class="body">
      <!-- 打印快照视图 -->
      <template v-if="snapshot">
        <div class="print-only title-page">
          <h1>{{ snapshot.nickname }} 的心屿成长档案</h1>
          <p>生成时间 {{ snapshot.generatedAt.slice(0, 16).replace('T', ' ') }} UTC · 由心屿漫行自助平台整理</p>
        </div>
        <SvCard>
          <h3 class="t">报告（{{ snapshot.reports.length }} 份）</h3>
          <div v-for="r in snapshot.reports" :key="r.id" class="snap-report">
            <b>{{ r.title }}</b>
            <span class="type">{{ TYPE_LABELS[r.type] || r.type }}</span>
            <small>{{ r.date }}</small>
            <template v-if="r.content && typeof r.content === 'object'">
              <p v-if="r.content.report?.insight" class="insight sv-muted">💡 {{ r.content.report.insight }}</p>
              <p v-if="r.content.planTitle" class="insight sv-muted">🌿 {{ r.content.planTitle }}</p>
              <p v-if="r.content.overall" class="insight sv-muted">沟通分 {{ r.content.overall.avgScore }}</p>
            </template>
          </div>
        </SvCard>
        <SvCard>
          <h3 class="t">周画像</h3>
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
        </SvCard>
        <SvCard>
          <h3 class="t">情绪记录（{{ snapshot.emotionPoints.length }} 点）</h3>
          <ul class="points">
            <li v-for="(p, i) in snapshot.emotionPoints" :key="i">
              {{ p.date }} · {{ p.emotion }}（效价 {{ p.valence }}，强度 {{ p.intensity }}）
            </li>
          </ul>
        </SvCard>
        <button class="sv-btn no-print" @click="doPrint"><SvIcon name="i-download" :size="18" tone="inherit" /> 打印 / 存为 PDF</button>
        <button class="sv-btn plain no-print" @click="snapshot = null">返回档案</button>
      </template>

      <!-- 常规档案视图 -->
      <template v-else-if="summary">
        <SvCard>
          <div class="week-head">
            <h3 class="t">{{ summary.statWeek }} 周报</h3>
            <span class="risk" :class="summary.profile.riskLevel.toLowerCase()">
              {{ riskLabel[summary.profile.riskLevel] || summary.profile.riskLevel }}
            </span>
            <span v-if="summary.profile.avgValence" class="sv-cap">平均效价 {{ summary.profile.avgValence }}</span>
          </div>
          <p v-if="summary.profile.stressorTop?.length" class="line sv-muted">
            压力源：<span v-for="s in summary.profile.stressorTop" :key="s.name" class="tag">{{ s.name }} ×{{ s.count }}</span>
          </p>
          <p v-if="summary.profile.distortionTop?.length" class="line sv-muted">
            思维误区线索：<span v-for="d in summary.profile.distortionTop" :key="d.name" class="tag warm">{{ d.name }} ×{{ d.count }}</span>
          </p>
        </SvCard>

        <SvList title="本周报告">
          <p v-if="!summary.reports?.length" class="empty sv-muted">本周还没有新报告。</p>
          <SvCell v-for="r in summary.reports" :key="r.id" :label="r.title"
            :hint="TYPE_LABELS[r.type] || r.type" icon="i-doc" :tone="riskTone(r.riskLevel)"
            :to="`/archive/report/${r.id}`" />
        </SvList>

        <SvList title="全部报告">
          <SvCell v-for="r in allReports" :key="r.id" :label="r.title"
            :hint="(TYPE_LABELS[r.type] || r.type) + ' · ' + (r.createdAt || '').slice(0, 10)"
            icon="i-doc" :tone="riskTone(r.riskLevel)" :to="`/archive/report/${r.id}`" />
          <button v-if="allReports.length < repTotal" class="sv-btn ghost more" @click="moreReports">加载更多</button>
        </SvList>

        <SvCard>
          <h3 class="t">本周情绪点（{{ summary.emotionPoints.length }}）</h3>
          <p v-if="!summary.emotionPoints.length" class="sv-muted">这周还没有记录。</p>
          <ul class="points">
            <li v-for="p in summary.emotionPoints" :key="p.date + p.emotion">
              {{ p.date }} · {{ p.emotion }}
              <small>{{ p.sourceType === 'DIARY' ? '日记' : p.sourceType === 'SIMULATION' ? '模拟' : p.sourceType === 'SELF_RATING' ? '打卡' : p.sourceType }}</small>
            </li>
          </ul>
        </SvCard>

        <SvCard v-if="summary.trainingScores?.length">
          <h3 class="t">训练分数趋势</h3>
          <ul class="points">
            <li v-for="(t, i) in summary.trainingScores" :key="i">{{ t.date || '—' }} · 综合分 {{ t.avgScore }}</li>
          </ul>
        </SvCard>

        <button class="sv-btn ghost" :disabled="exporting" @click="exportArchive">
          <SvIcon name="i-export" :size="18" tone="inherit" /> {{ exporting ? '生成快照中…' : '导出我的档案（限时一次性链接）' }}</button>

        <SvDisclaimer class="d" text="本档案为自助记录汇总，不构成任何医学诊断"
          reason="档案内容全部来自你自己的记录与 AI 梳理结果，导出快照领取后会立即作废。" />
      </template>

      <template v-else-if="!loading">
        <p class="sv-muted none">档案加载中出了问题，返回重试。</p>
        <button class="sv-btn ghost" @click="router.back()">返回</button>
      </template>
      <p v-else class="sv-muted none">档案加载中…</p>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.ic { border: none; background: transparent; color: var(--sv-indigo); cursor: pointer; width: 44px; height: 44px; display: grid; place-items: center; }
.ic:disabled { opacity: .5; }
.t { font-size: var(--sv-fs-title3); }
.week-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.risk { font-size: var(--sv-fs-caption1); border-radius: var(--sv-r-pill); padding: 1px 8px; }
.risk.low { background: color-mix(in srgb, var(--sv-mint) 14%, var(--sv-card)); color: var(--sv-mint); }
.risk.medium { background: color-mix(in srgb, var(--sv-amber) 16%, var(--sv-card)); color: var(--sv-amber); }
.risk.high { background: color-mix(in srgb, var(--sv-red) 14%, var(--sv-card)); color: var(--sv-red); }
.line { margin-top: 6px; font-size: var(--sv-fs-caption1); }
.tag { background: var(--sv-fill2); border-radius: 6px; padding: 1px 8px; margin: 0 4px 2px 0; display: inline-block; }
.tag.warm { background: color-mix(in srgb, var(--sv-amber) 14%, transparent); color: var(--sv-amber); }
.empty { padding: 10px 16px; }
.more { width: auto; display: block; margin: 4px auto 8px; }
.points li { padding: 7px 0; border-bottom: 1px dashed var(--sv-sep); font-size: var(--sv-fs-footnote); display: flex; gap: 8px; }
.points li:last-child { border-bottom: none; }
.points small { color: var(--sv-label3); margin-left: auto; }
.snap-report { border: 1px solid var(--sv-sep); border-radius: var(--sv-r-ctl); padding: 10px 12px; margin-bottom: var(--sv-s2); font-size: var(--sv-fs-subhead); }
.snap-report small { color: var(--sv-label3); margin-left: 8px; }
.snap-report .type { font-size: var(--sv-fs-caption1); color: var(--sv-indigo); margin-left: 8px; }
.insight { margin: 6px 0 0; font-size: var(--sv-fs-footnote); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--sv-fs-footnote); }
.tbl th { text-align: left; color: var(--sv-label2); font-weight: 500; border-bottom: 1px solid var(--sv-sep); padding: 6px 4px; }
.tbl td { padding: 6px 4px; border-bottom: 1px dashed var(--sv-sep); }
.d { margin-top: var(--sv-s3); }
.none { text-align: center; padding: 40px 0; }
.title-page { display: none; }
.print-only { display: none; }

@media print {
  .no-print { display: none !important; }
  .print-only, .title-page { display: block; }
  .title-page h1 { font-size: 22px; margin-bottom: 4px; }
  .body { padding: 0; }
  :deep(.sv-card) { box-shadow: none; border: 1px solid #eee; break-inside: avoid; }
}
</style>
