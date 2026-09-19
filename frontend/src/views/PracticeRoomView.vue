<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import http, { type ApiResp } from '@/api/http'
import { streamTask } from '@/api/sse'
import { confirmDialog, toast } from '@/stores/ui'
import { useContentStore, type ExerciseDef } from '@/stores/content'

/* C4 沉浸跟练（手册 下篇·C4）：五种练习各配专属沉浸形态，
   产出统一走 /exercise-records（挂计划回写 G4），认知书写另走 COGNITIVE_PIPELINE。 */

const route = useRoute()
const content = useContentStore()

const code = String(route.params.id || '')
const planId = String(route.query.planId || '') || undefined
const planItemSeq = route.query.planItemSeq || route.query.seq ? Number(route.query.planItemSeq ?? route.query.seq) : undefined
const scheduledDate = String(route.query.scheduled || '') || undefined

const ex = ref<ExerciseDef | null>(null)
const loadError = ref('')
const startedAt = Date.now()
const elapsed = ref(0)        // 秒，仅计时展示与 durationActual 换算
const finished = ref(false)   // 已完成上报，退出不再确认
const saving = ref(false)

let tick: ReturnType<typeof setInterval> | undefined

onMounted(async () => {
  tick = setInterval(() => { elapsed.value = Math.floor((Date.now() - startedAt) / 1000) }, 1000)
  try {
    const list = await content.ensureExercises()
    ex.value = list.find((e) => e.id === code) || null
    if (!ex.value) loadError.value = '不认识这个练习，请从练习页重新进入'
    else stepDone.value = ex.value.steps.map(() => false)
  } catch {
    loadError.value = '练习目录加载失败，稍后重试'
  }
})
onUnmounted(() => { clearInterval(tick); stopBreath(); stopGround(); cbtAbort?.abort() })

const mode = computed(() => {
  if (code === 'ex_478_breath') return 'breath'
  if (code === 'ex_54321') return 'ground'
  if (code === 'ex_cbt_write') return 'cbt'
  if (code === 'ex_action') return 'action'
  return 'steps'
})
const fmtClock = computed(() => {
  const m = Math.floor(elapsed.value / 60), s = elapsed.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

/* 中途退出：确认；放弃则记一条 INTERRUPTED（completed=false），不伪造完成 */
onBeforeRouteLeave(async () => {
  if (finished.value || loadError.value) return true
  const ok = await confirmDialog({ title: '要离开跟练吗？', message: '没做完也没关系——我会记下你来了这一步。' })
  if (!ok) return false
  if (mode.value !== 'cbt') {
    await http.post('/exercise-records', { exerciseId: code, completed: false,
      planId, planItemSeq, scheduledDate, durationActual: Math.max(1, Math.round(elapsed.value / 60)) }).catch(() => {})
  }
  return true
})

async function report(star?: number, feedback?: string) {
  if (saving.value) return false
  saving.value = true
  try {
    await http.post('/exercise-records', {
      exerciseId: code, completed: true, planId, planItemSeq, scheduledDate,
      durationActual: Math.max(1, Math.round(elapsed.value / 60)),
      ratingStar: star, feedback,
    })
    finished.value = true
    return true
  } catch (e: any) {
    toast(e.message || '打卡没写上，再试一次')
    return false
  } finally {
    saving.value = false
  }
}

/* ---------- ① 呼吸环 4-7-8 ---------- */
const PHASES = [
  { name: '吸气', secs: 4, scale: 1, dur: '4s' },
  { name: '屏息', secs: 7, scale: 1, dur: '0s' },
  { name: '呼气', secs: 8, scale: 0.45, dur: '8s' },
] as const
const breathOn = ref(false)
const phaseIdx = ref(0)
const phaseLeft = ref(0)
const cycles = ref(0)
const CYCLE_GOAL = 4
let breathTimer: ReturnType<typeof setInterval> | undefined

const phase = computed(() => PHASES[phaseIdx.value])
function startBreath() {
  stopBreath()
  cycles.value = 0
  phaseIdx.value = 0
  phaseLeft.value = PHASES[0].secs
  breathOn.value = true
  breathTimer = setInterval(() => {
    phaseLeft.value -= 1
    if (phaseLeft.value > 0) return
    if (phaseIdx.value < 2) { phaseIdx.value += 1; phaseLeft.value = PHASES[phaseIdx.value].secs; return }
    cycles.value += 1
    if (cycles.value >= CYCLE_GOAL) { stopBreath(); return }
    phaseIdx.value = 0; phaseLeft.value = PHASES[0].secs
  }, 1000)
}
function stopBreath() { clearInterval(breathTimer); breathOn.value = false }
const breathDone = computed(() => cycles.value >= CYCLE_GOAL)

/* ---------- ② 54321 着陆 ---------- */
const GROUND = [
  { n: 5, step: '看', desc: '环顾四周，说出你看到的 5 样东西' },
  { n: 4, step: '听', desc: '安静下来，说出你听到的 4 种声音' },
  { n: 3, step: '触', desc: '触摸 3 样物品，感受它们的质地' },
  { n: 2, step: '闻', desc: '辨认 2 种气味' },
  { n: 1, step: '尝', desc: '感受 1 种味道，或做一次深呼吸' },
] as const
const gIdx = ref(0)
const gOn = ref(false)
const gLeft = ref(30)
let groundTimer: ReturnType<typeof setInterval> | undefined
function startGround() {
  stopGround()
  gIdx.value = 0; gLeft.value = 30; gOn.value = true
  groundTimer = setInterval(() => { if (gOn.value && gLeft.value > 0) gLeft.value -= 1 }, 1000)
}
function stopGround() { clearInterval(groundTimer); gOn.value = false }
function groundNext() {
  if (gIdx.value < 4) { gIdx.value += 1; gLeft.value = 30 }
  else { stopGround(); gIdx.value = 5 }
}
const groundDone = computed(() => gIdx.value >= 5)

/* ---------- ③ 认知书写三栏 ---------- */
const cbt = ref({ situation: '', autoThought: '', alternativeThought: '', reappraisal: 5 })
const cbtPhase = ref<'form' | 'waiting' | 'questions'>('form')
const cbtQuestions = ref<string[]>([])
const cbtError = ref('')
let cbtAbort: AbortController | undefined = new AbortController()

async function submitCbt() {
  const { situation, autoThought, alternativeThought } = cbt.value
  if (!situation.trim() || !autoThought.trim() || !alternativeThought.trim()) {
    toast('三栏都写一写吧，哪怕各一句话')
    return
  }
  cbtPhase.value = 'waiting'
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>('/exercises/cognitive-writing', {
      situation: situation.trim(), autoThought: autoThought.trim(),
      alternativeThought: alternativeThought.trim(),
      planId, planItemSeq, durationActual: Math.max(1, Math.round(elapsed.value / 60)),
    })
    finished.value = true   // 后端已自动完成该练习并回写计划
    await streamTask(data.data.taskNo, (e) => {
      if (e.event === 'middle_result' && e.data.agent === 'TRACE') {
        cbtQuestions.value = e.data.payload?.socraticQuestions || []
        cbtPhase.value = 'questions'
      } else if (e.event === 'done') {
        if (cbtPhase.value === 'waiting') cbtPhase.value = 'questions'
      } else if (e.event === 'error') {
        cbtError.value = e.data.message || '追问生成失败，写下来的你已经很棒了'
        cbtPhase.value = 'questions'
      }
    }, cbtAbort?.signal)
  } catch (err: any) {
    if (err?.message === 'aborted') return
    cbtError.value = err?.message || '提交失败，内容还在，再试一次'
    cbtPhase.value = 'form'
  }
}

/* ---------- ④ 行为激活 ---------- */
const ACTION_TASKS = [
  '起身倒一杯水，慢慢喝完', '开窗晒 1 分钟太阳', '给一盆植物浇水',
  '整理桌面 3 分钟', '给一个人发一句问候', '下楼走 5 分钟',
  '放一首歌跟着哼完', '洗一把脸，注意水的温度',
]
const picked = ref<string[]>([])
const stars = ref(0)
async function finishAction() {
  if (!picked.value.length) { toast('先选 1-2 件 5 分钟内能完成的小事'); return }
  if (!stars.value) { toast('做完后给此刻心情打个分吧（1-5 星）'); return }
  if (await report(stars.value, `完成了：${picked.value.join('、')}`)) toast('已记录，行动本身就是照顾自己')
}

/* ---------- ⑤ 通用步骤跟练 ---------- */
const stepDone = ref<boolean[]>([])
const allStepsDone = computed(() => stepDone.value.length > 0 && stepDone.value.every(Boolean))
async function finishSteps(completed: boolean) {
  if (!completed) {
    const ok = await confirmDialog({ title: '今天先不做？', message: '没关系，来了就已经是照顾自己。' })
    if (!ok) return
  }
  if (await report(undefined, completed ? undefined : '今天先不做'))
    toast(completed ? '跟练完成，这一步值得被记住' : '已记录，随时可以回来')
}
</script>

<template>
  <div class="room">
    <SvNavBar :title="ex?.name || '沉浸跟练'" :subtitle="loadError ? '' : `已进行 ${fmtClock} · 约 ${ex?.durationMin || '—'} 分钟`"
      back="练习" back-to="/practice" :large="false" />

    <div class="body">
      <p v-if="loadError" class="sv-muted err">{{ loadError }}</p>

      <!-- ① 呼吸环 -->
      <template v-else-if="mode === 'breath'">
        <div class="stage">
          <div class="ring-wrap" aria-live="polite">
            <div class="halo" :style="{
              transform: `scale(${!breathOn ? 0.45 : phase.scale})`,
              transitionDuration: !breathOn ? '0.6s' : phase.dur }" />
            <div class="ring-mid">
              <b>{{ breathOn ? phase.name : '准备好了吗' }}</b>
              <span v-if="breathOn">{{ Math.max(0, phaseLeft) }}</span>
              <span v-else class="sv-cap">4 吸 · 7 停 · 8 呼</span>
            </div>
          </div>
          <p class="sv-cap cyc">{{ breathOn ? `第 ${Math.min(cycles + 1, CYCLE_GOAL)} / ${CYCLE_GOAL} 轮 · ${phase.name} ${Math.max(0, phaseLeft)} 秒` : '跟随光圈呼吸，不用憋得太勉强' }}</p>
          <div class="acts">
            <button v-if="!breathOn && !breathDone" class="sv-btn" @click="startBreath">开始呼吸环</button>
            <button v-else-if="breathOn" class="sv-btn ghost" @click="stopBreath">暂停</button>
            <button v-if="breathDone && !finished" class="sv-btn" @click="report()">做完 4 轮啦，打卡</button>
          </div>
          <p v-if="breathDone && !finished" class="sv-cap hint2">四组 4-7-8 走完了——身体通常会先松一点。</p>
        </div>
      </template>

      <!-- ② 54321 着陆 -->
      <template v-else-if="mode === 'ground'">
        <div class="stage">
          <template v-if="!groundDone">
            <p v-if="!gOn" class="sv-cap intro">把注意力带回此刻的房间里。准备好了就开始。</p>
            <div v-else class="grounding" aria-live="polite">
              <span class="gnum">{{ GROUND[gIdx].n }}</span>
              <h3>{{ GROUND[gIdx].step }}</h3>
              <p class="sv-muted">{{ GROUND[gIdx].desc }}</p>
              <p class="sv-cap gtime">{{ gLeft > 0 ? `慢慢来，还有 ${gLeft} 秒` : '可以继续，也可以走下一步' }}</p>
            </div>
            <div class="acts">
              <button v-if="!gOn" class="sv-btn" @click="startGround">开始着陆</button>
              <button v-else class="sv-btn" @click="groundNext">{{ gIdx < 4 ? `下一步 · 听 ${GROUND[gIdx + 1].n} 种` : '我做到了，完成' }}</button>
              <button v-if="gOn" class="sv-btn plain" @click="stopGround">暂停</button>
            </div>
          </template>
          <template v-else>
            <p class="done-big">🌿 你把自己带回了此刻。</p>
            <div class="acts"><button v-if="!finished" class="sv-btn" @click="report()">打卡这一分钟</button></div>
          </template>
        </div>
      </template>

      <!-- ③ 认知书写 -->
      <template v-else-if="mode === 'cbt'">
        <SvCard v-if="cbtPhase === 'form'">
          <h3 class="t">三栏书写</h3>
          <div class="field"><label>情境 · 只写事实，像监控摄像头拍到的</label>
            <textarea v-model="cbt.situation" rows="2" placeholder="昨晚开会时，我的发言被打断了…" /></div>
          <div class="field"><label>自动想法 · 当时脑子里冒出的那句话</label>
            <textarea v-model="cbt.autoThought" rows="2" placeholder="「我说的东西没人想听」" /></div>
          <div class="field"><label>替代想法 · 如果你的朋友这样讲，你会怎么为他辩护？</label>
            <textarea v-model="cbt.alternativeThought" rows="2" placeholder="「被打断是他的习惯，不代表我的话没价值」" /></div>
          <div class="field"><label>此刻情绪强度（0 最轻 - 10 最重）：{{ cbt.reappraisal }}</label>
            <input v-model.number="cbt.reappraisal" type="range" min="0" max="10" aria-label="情绪强度重评" /></div>
          <button class="sv-btn" @click="submitCbt">写给心屿看看</button>
          <SvDisclaimer class="disc" text="写下的内容会进入情绪梳理管道（信封加密）；追问是参考，不是评判。" />
        </SvCard>
        <SvCard v-else-if="cbtPhase === 'waiting'">
          <p class="sv-muted wait">心屿正在轻轻读你写的字…</p>
        </SvCard>
        <SvCard v-else>
          <h3 class="t">带着这三句话，再想一想</h3>
          <p v-if="cbtError" class="sv-muted">{{ cbtError }}</p>
          <ol v-if="cbtQuestions.length" class="qs">
            <li v-for="(q, i) in cbtQuestions" :key="i">{{ q }}</li>
          </ol>
          <p v-else class="sv-muted">这次追问没生成，但你已经把想法从「自动化」写成了「可审视」——这本身就是收获。</p>
          <p class="sv-cap">今天情绪强度从写前的它，到现在的 {{ cbt.reappraisal }}/10。已自动计入今日跟练。</p>
        </SvCard>
      </template>

      <!-- ④ 行为激活 -->
      <template v-else-if="mode === 'action'">
        <SvCard>
          <h3 class="t">挑 1-2 件 5 分钟内的小事</h3>
          <div class="tasks">
            <button v-for="t in ACTION_TASKS" :key="t" class="task" :class="{ on: picked.includes(t) }"
              :aria-pressed="picked.includes(t)" @click="picked.includes(t) ? picked.splice(picked.indexOf(t), 1) : picked.length < 2 && picked.push(t)">
              {{ t }}</button>
          </div>
        </SvCard>
        <SvCard v-if="picked.length">
          <h3 class="t">去做完，回来告诉心屿：现在心情几星？</h3>
          <div class="stars" role="radiogroup" aria-label="完成后心情评分">
            <button v-for="s in 5" :key="s" class="star" :class="{ on: stars >= s }" role="radio"
              :aria-checked="stars === s" :aria-label="`${s} 星`" @click="stars = s">★</button>
          </div>
          <button class="sv-btn" :disabled="saving" style="margin-top: 12px" @click="finishAction">
            {{ saving ? '正在记录…' : '完成并打卡' }}</button>
        </SvCard>
      </template>

      <!-- ⑤ 通用步骤 -->
      <template v-else>
        <SvCard>
          <h3 class="t">{{ ex?.name }}</h3>
          <div v-for="(s, i) in ex?.steps || []" :key="i" class="step" :class="{ on: stepDone[i] }">
            <label class="step-l">
              <input type="checkbox" v-model="stepDone[i]" :aria-label="s.step" />
              <span><b>{{ i + 1 }}. {{ s.step }}</b><p class="sv-muted">{{ s.desc }}</p></span>
            </label>
          </div>
          <div class="acts">
            <button class="sv-btn" :disabled="!allStepsDone || saving" @click="finishSteps(true)">全部完成，打卡</button>
            <button class="sv-btn ghost" @click="finishSteps(false)">今天先不做</button>
          </div>
        </SvCard>
      </template>

      <!-- 完成态 -->
      <SvCard v-if="finished" class="fin">
        <p class="done-big">✅ 这一段时间是你的了。</p>
        <p class="sv-muted">进度已写进计划{{ planId ? '，' : '（今天没挂计划，也算一次照顾）' }}。成就判定在后台跑，可能会亮一枚新的。</p>
        <div class="acts">
          <button class="sv-btn" @click="$router.replace('/today')">回今日</button>
          <button class="sv-btn plain" @click="$router.replace('/practice')">看全部计划</button>
        </div>
      </SvCard>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.err, .wait { text-align: center; padding: var(--sv-s5) 0; }
.stage { text-align: center; padding: var(--sv-s4) 0; }
.intro { padding: var(--sv-s4) 0; line-height: 1.7; }
.ring-wrap { position: relative; width: 250px; height: 250px; margin: 0 auto; display: grid; place-items: center; }
.halo { position: absolute; inset: 0; border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--sv-mint) 38%, transparent), color-mix(in srgb, var(--sv-indigo) 22%, transparent) 70%);
  transition-property: transform; transition-timing-function: ease-in-out; transform: scale(0.45); }
.ring-mid { position: relative; display: grid; place-items: center; gap: 4px; }
.ring-mid b { font-size: var(--sv-fs-title3); }
.ring-mid span { font-size: 44px; font-weight: 200; font-variant-numeric: tabular-nums; color: var(--sv-indigo); }
.cyc { margin-top: var(--sv-s3); }
.grounding { padding: var(--sv-s4) 0; }
.gnum { font-size: 96px; font-weight: 200; line-height: 1; color: var(--sv-indigo); font-variant-numeric: tabular-nums; }
.grounding h3 { font-size: var(--sv-fs-title2); margin: 8px 0 4px; }
.gtime { margin-top: var(--sv-s3); }
.acts { display: flex; gap: var(--sv-s2); justify-content: center; margin-top: var(--sv-s4); flex-wrap: wrap; }
.acts .sv-btn { width: auto; padding-inline: 22px; }
.hint2 { margin-top: var(--sv-s2); }
.t { font-size: var(--sv-fs-title3); margin-bottom: var(--sv-s3); }
.field { margin-bottom: var(--sv-s3); }
.field label { display: block; font-size: var(--sv-fs-footnote); color: var(--sv-label2); margin-bottom: 6px; }
.field textarea { width: 100%; border: 1px solid var(--sv-sep); background: var(--sv-card); border-radius: var(--sv-r-ctl);
  padding: 10px 12px; font-size: var(--sv-fs-subhead); font-family: inherit; resize: vertical; color: var(--sv-label); outline: none; }
.field textarea:focus { border-color: var(--sv-indigo); }
.field input[type="range"] { width: 100%; accent-color: var(--sv-indigo); }
.disc { margin-top: var(--sv-s2); }
.qs { padding-left: 20px; display: grid; gap: 10px; }
.qs li { line-height: 1.7; font-size: var(--sv-fs-subhead); }
.tasks { display: flex; flex-wrap: wrap; gap: 8px; }
.task { border: 1px solid var(--sv-sep); background: var(--sv-card); color: var(--sv-label); border-radius: var(--sv-r-pill);
  padding: 8px 14px; font-size: var(--sv-fs-footnote); cursor: pointer; font-family: inherit; }
.task.on { background: var(--sv-indigo-soft); border-color: var(--sv-indigo); color: var(--sv-indigo); font-weight: 600; }
.stars { display: flex; gap: 6px; justify-content: center; margin: 8px 0 4px; }
.star { border: none; background: transparent; font-size: 30px; color: var(--sv-fill3); cursor: pointer; padding: 2px 4px; }
.star.on { color: var(--sv-amber); }
.step { border-radius: var(--sv-r-ctl); margin-bottom: var(--sv-s2); background: var(--sv-fill3); }
.step.on { background: color-mix(in srgb, var(--sv-mint) 12%, var(--sv-card)); }
.step-l { display: flex; gap: var(--sv-s3); align-items: flex-start; padding: 12px; cursor: pointer; }
.step-l input { margin-top: 3px; width: 18px; height: 18px; accent-color: var(--sv-indigo); }
.step-l b { font-size: var(--sv-fs-subhead); }
.step-l p { margin-top: 3px; }
.done-big { font-size: var(--sv-fs-title3); text-align: center; padding: var(--sv-s3) 0; }
.fin { margin-top: var(--sv-s3); }
@media (prefers-reduced-motion: reduce) { .halo { transition: none; } }
</style>
