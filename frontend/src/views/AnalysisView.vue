<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvTimeline, { type FlowStep } from '@/components/ui/SvTimeline.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import { streamTask } from '@/api/sse'
import type { Json } from '@/api/http'
import { EMOTION_BY_LABEL } from '@/utils/emotions'
import { useContentStore } from '@/stores/content'

const route = useRoute()
const router = useRouter()
const content = useContentStore()

const steps = ref<FlowStep[]>([])
const results = ref<Record<string, Json>>({})
const error = ref('')
const running = ref(true)
const abort = new AbortController()

const emotion = computed(() => results.value.EMOTION ?? null)
const trace = computed(() => results.value.TRACE ?? null)
const support = computed(() => results.value.SUPPORT ?? null)
const receipt = computed(() => results.value.RISK_ARCHIVE ?? null)
const showCrisis = computed(
  () => receipt.value && (receipt.value.referral?.show || receipt.value.riskLevel === 'HIGH'),
)
const emoMeta = computed(() => emotion.value && EMOTION_BY_LABEL[emotion.value.primaryEmotion])

const exerciseNames = computed(() => Object.fromEntries((content.exercises ?? []).map(x => [x.id, x])))

const REPORT_TITLES: Record<string, string> = {
  eventSummary: '事件',
  emotionSummary: '情绪',
  thoughtSummary: '想法',
  insight: '洞察',
  suggestion: '下一步',
}

function addStep(agent: string, stepSeq: number) {
  if (!steps.value.find(x => x.agent === agent)) {
    steps.value.push({ agent, stepSeq, state: 'pending' })
    steps.value.sort((a, b) => a.stepSeq - b.stepSeq)
  }
}
function mark(agent: string, state: FlowStep['state'], sub?: string) {
  const s = steps.value.find(x => x.agent === agent)
  if (s) {
    s.state = state
    if (sub) s.sub = sub
  }
}

function toggle(agent: string) {
  document.getElementById(`block-${agent}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

onMounted(async () => {
  content.ensureExercises().catch(() => {
    /* 目录失败不挡流程 */
  })
  const taskNo = String(route.query.task || '')
  if (!taskNo) {
    error.value = '缺少任务号，请回到日记页重新提交'
    running.value = false
    return
  }
  try {
    // M5 的 SSE 通道：断线自动重连 + Last-Event-ID 重放，刷新本页也能续上
    await streamTask(
      taskNo,
      e => {
        switch (e.event) {
          case 'step_started':
            addStep(e.data.agent, e.data.stepSeq)
            mark(e.data.agent, 'running')
            break
          case 'middle_result':
            results.value = { ...results.value, [e.data.agent]: e.data.payload }
            mark(e.data.agent, 'done')
            break
          case 'step_failed':
            mark(e.data.agent, 'degraded')
            break
          case 'done':
            running.value = false
            if (e.data.status === 'FAILED') error.value = '任务未完成，请稍后重试'
            break
          case 'error':
            error.value = e.data.message || '服务异常'
            running.value = false
        }
      },
      abort.signal,
    )
  } catch (e) {
    error.value = (e as Error).message || '进度连接中断'
  } finally {
    running.value = false
  }
})
onUnmounted(() => abort.abort())
</script>

<template>
  <div class="analysis">
    <SvNavBar title="心屿梳理" back="日记" back-to="/diaries" :large="false">
      <template #actions>
        <router-link to="/diaries" class="ic" aria-label="回日记本"
          ><SvIcon name="i-diary" :size="20"
        /></router-link>
      </template>
    </SvNavBar>

    <div class="body">
      <SvCard>
        <SvTimeline :steps="steps" @toggle="toggle" />
        <p v-if="running && !steps.length" class="sv-muted">正在排队，多 Agent 马上开工…</p>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
      </SvCard>

      <!-- ① 情绪感知 -->
      <SvCard v-if="emotion" id="block-EMOTION">
        <h3 class="t">
          今日情绪
          <span class="emo" :style="{ color: `var(${emoMeta?.varName || '--e-numb'})` }">
            {{ emoMeta?.face || '🙂' }} {{ emotion.primaryEmotion }}</span
          >
        </h3>
        <div class="bars">
          <div class="bar">
            <span>强度</span>
            <div class="track"><div class="fill" :style="{ width: emotion.intensity * 100 + '%' }" /></div>
            <b>{{ Math.round(emotion.intensity * 100) }}%</b>
          </div>
          <div class="bar">
            <span>效价</span>
            <div class="track">
              <div
                class="fill v"
                :class="emotion.valence < 0 ? 'neg' : 'pos'"
                :style="{ width: Math.abs(emotion.valence) * 100 + '%' }"
              />
            </div>
            <b>{{ emotion.valence }}</b>
          </div>
        </div>
        <div v-if="emotion.eventTags?.length" class="tags">
          <span v-for="t in emotion.eventTags" :key="t.tag" class="tag">
            {{ t.tag }}<em v-if="t.evidence">「{{ t.evidence }}」</em>
          </span>
        </div>
      </SvCard>

      <!-- ② 溯源推理 -->
      <SvCard v-if="trace" id="block-TRACE">
        <h3 class="t">溯源复盘</h3>
        <p v-if="trace.insufficientEvidence" class="sv-muted">
          这次记录有点少，先不下结论，想到什么再补一句。
        </p>

        <template v-if="trace.stressors?.length">
          <h4>可能的压力来源</h4>
          <ul class="stressors">
            <li v-for="s in trace.stressors" :key="s.source">
              <b>{{ s.source }}</b
              ><em class="conf">{{ Math.round(s.confidence * 100) }}%</em>
              <span v-for="(ev, i) in s.evidence" :key="i" class="ev">「{{ ev }}」</span>
            </li>
          </ul>
        </template>

        <template v-if="trace.cognitiveDistortions?.length">
          <h4>思维误区线索</h4>
          <div v-for="d in trace.cognitiveDistortions" :key="d.kgNodeId" class="dist">
            <b>{{ d.name }}</b>
            <p class="sv-muted">触发：{{ d.trigger }}</p>
            <p class="q">🤔 {{ d.challengeQuestion }}</p>
          </div>
        </template>

        <template v-if="trace.socraticQuestions?.length">
          <h4>留给你慢慢想的问题</h4>
          <ol class="socratic">
            <li v-for="(qq, i) in trace.socraticQuestions" :key="i">{{ qq }}</li>
          </ol>
        </template>

        <template v-if="trace.report">
          <h4>事件-想法-情绪复盘</h4>
          <dl class="report">
            <template v-for="(v, k) in trace.report" :key="k">
              <dt>{{ REPORT_TITLES[k as string] || k }}</dt>
              <dd>{{ v }}</dd>
            </template>
          </dl>
        </template>
        <SvDisclaimer
          class="disc"
          text="以上为自助梳理参考，不构成任何诊断"
          reason="结论由溯源 Agent 基于你自己的记录、对照心理学知识库（CBG 图谱）推理得出，只描述可能性，不做判断。"
        />
      </SvCard>

      <!-- ③ 疏导干预 -->
      <SvCard v-if="support" id="block-SUPPORT">
        <h3 class="t">🌿 {{ support.planTitle }}</h3>
        <div v-for="m in support.matchedExercises" :key="m.exerciseId" class="plan-item">
          <div class="pi-head">
            <b>{{ exerciseNames[m.exerciseId]?.name || m.exerciseId }}</b>
            <em class="when">{{ m.schedule }}</em>
          </div>
          <p>{{ m.reason }}</p>
          <small v-if="exerciseNames[m.exerciseId]">
            {{ exerciseNames[m.exerciseId].steps.map((s: any) => s.step).join(' → ') }} · 约
            {{ exerciseNames[m.exerciseId].durationMin }} 分钟</small
          >
        </div>
        <div v-if="support.psyEducation" class="psy">
          <h4>心理小课堂 · {{ support.psyEducation.topic }}</h4>
          <p class="sv-muted">{{ support.psyEducation.content }}</p>
        </div>
        <button class="sv-btn ghost" @click="router.push('/practice')">去跟练打卡 →</button>
      </SvCard>

      <!-- ④ 风险归档回执（仅需要关照时展示） -->
      <CrisisReferral
        v-if="showCrisis"
        :level="receipt.riskLevel === 'HIGH' ? 'HIGH' : 'MEDIUM'"
        :headline="receipt.referral?.headline"
      />

      <div v-if="!running && !error" class="fin">
        <button class="sv-btn" @click="router.replace('/diaries')">回日记本看看</button>
        <button class="sv-btn plain" @click="router.push('/insights')">情绪洞察 →</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.body {
  padding: 0 var(--sv-s4);
}
.ic {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--sv-indigo);
}
.err {
  color: var(--sv-red);
  font-size: var(--sv-fs-footnote);
}
.t {
  font-size: var(--sv-fs-title3);
  margin-bottom: var(--sv-s3);
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.emo {
  font-weight: 700;
}
.bars {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
  margin-bottom: var(--sv-s3);
}
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
}
.bar span {
  width: 2.5em;
}
.bar b {
  font-variant-numeric: tabular-nums;
  color: var(--sv-label);
}
.track {
  flex: 1;
  height: 8px;
  background: var(--sv-fill2);
  border-radius: 4px;
  overflow: hidden;
}
.fill {
  height: 100%;
  border-radius: 4px;
  background: var(--sv-amber);
}
.fill.pos {
  background: var(--sv-mint);
}
.fill.neg {
  background: var(--sv-amber);
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag {
  font-size: var(--sv-fs-caption1);
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
  border-radius: var(--sv-r-pill);
  padding: 4px 10px;
}
.tag em {
  font-style: normal;
  opacity: 0.75;
  margin-left: 4px;
}
h4 {
  margin: var(--sv-s4) 0 var(--sv-s2);
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  font-weight: 600;
}
.stressors li {
  padding: 8px 0;
  border-bottom: 1px dashed var(--sv-sep);
  font-size: var(--sv-fs-subhead);
}
.stressors li:last-child {
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
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
  margin-top: 2px;
}
.dist {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
}
.dist .q {
  margin-top: 6px;
  font-size: var(--sv-fs-subhead);
}
.socratic {
  padding-left: 22px;
  list-style: decimal;
  color: var(--sv-label);
  font-size: var(--sv-fs-subhead);
  line-height: 1.8;
}
.report {
  font-size: var(--sv-fs-subhead);
}
.report dt {
  float: left;
  clear: left;
  width: 4.5em;
  color: var(--sv-label2);
}
.report dd {
  margin: 0 0 8px 5em;
}
.disc {
  margin-top: var(--sv-s4);
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
  color: var(--sv-label);
}
.plan-item small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
}
.when {
  font-style: normal;
  font-size: var(--sv-fs-caption1);
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
  border-radius: var(--sv-r-pill);
  padding: 1px 8px;
}
.psy h4 {
  margin-bottom: 4px;
}
.psy p {
  line-height: 1.7;
}
.fin {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
  margin-top: var(--sv-s4);
}
</style>
