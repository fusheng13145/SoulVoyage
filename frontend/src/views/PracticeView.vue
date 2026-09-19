<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvCheckbox from '@/components/ui/SvCheckbox.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import http, { type ApiResp } from '@/api/http'
import { toast } from '@/stores/ui'
import { useContentStore, type ExerciseDef } from '@/stores/content'

interface PlanExercise { exerciseId: string; reason: string; schedule: string }
interface Plan { planTitle: string; matchedExercises: PlanExercise[]; psyEducation?: { topic: string; content: string } }
interface ExRecord { id: number; exerciseName: string; completed: boolean; feedback: string; createdAt: string }

const router = useRouter()
const content = useContentStore()

const exercises = computed<ExerciseDef[]>(() => content.exercises ?? [])
const plan = ref<Plan | null>(null)
const records = ref<ExRecord[]>([])
const active = ref<string | null>(null)          // 跟练中的练习 id
const doneSteps = ref<boolean[]>([])
const checkinMsg = ref('')
const loading = ref(true)

const byId = computed(() => Object.fromEntries(exercises.value.map((e) => [e.id, e])))
const activeEx = computed(() => active.value ? byId.value[active.value] : null)

async function load() {
  loading.value = true
  try {
    const [, rec] = await Promise.all([
      content.ensureExercises(),
      http.get<ApiResp<ExRecord[]>>('/exercise-records', { params: { limit: 10 } }),
    ])
    records.value = rec.data.data
    try {
      // 最新疏导方案（SUPPORT 报告）
      const { data } = await http.get<ApiResp<{ items: { id: number; type: string }[] }>>('/reports', {
        params: { page: 0, size: 20 },
      })
      const sup = data.data.items.find((i) => i.type === 'SUPPORT')
      if (sup) {
        const d = await http.get<ApiResp<{ content: Plan }>>(`/reports/${sup.id}`)
        plan.value = d.data.data.content
      }
    } catch { /* 无方案时仅展示练习库 */ }
  } finally {
    loading.value = false
  }
}
onMounted(() => load().catch(() => toast('加载失败')))

function startFollow(id: string) {
  active.value = id
  doneSteps.value = (byId.value[id]?.steps ?? []).map(() => false)
  checkinMsg.value = ''
  document.getElementById('sv-follow')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function checkin(completed: boolean) {
  if (!active.value) return
  if (completed && doneSteps.value.some((d) => !d)) {
    checkinMsg.value = '还有步骤没走完，先慢慢做完再说 🙂'
    return
  }
  try {
    await http.post('/exercise-records', { exerciseId: active.value, completed })
    checkinMsg.value = completed ? '已打卡，照顾自己的动作值得被记住。' : '已记录，随时可以回来继续。'
    records.value.unshift({
      id: Date.now(), exerciseName: byId.value[active.value].name, completed, feedback: '',
      createdAt: new Date().toISOString(),
    })
    if (completed) active.value = null
  } catch (e: any) {
    toast(e.message || '打卡失败')
  }
}

const fmtTime = (s: string) => (s ? s.slice(0, 16).replace('T', ' ') : '')
</script>

<template>
  <div class="practice">
    <SvNavBar title="练习" />

    <div class="body">
      <!-- 模拟训练入口 -->
      <SvCard :pad="false">
        <button class="hero" @click="router.push('/practice/sim')">
          <span class="h-ico"><SvIcon name="i-heart" :size="26" tone="inherit" /></span>
          <span class="h-txt">
            <b>人际模拟训练</b>
            <small>和「数字人」安全地练一场不好开口的对话 · 4 场景 · NVO 逐轮复盘</small>
          </span>
          <SvIcon name="i-arrow" :size="18" tone="label2" />
        </button>
      </SvCard>

      <!-- ① 心屿给的最新方案 -->
      <SvCard v-if="plan">
        <h3 class="t">🌿 {{ plan.planTitle }}</h3>
        <div v-for="m in plan.matchedExercises" :key="m.exerciseId" class="match">
          <div class="match-head">
            <b>{{ byId[m.exerciseId]?.name || m.exerciseId }}</b>
            <em class="sched">{{ m.schedule }}</em>
          </div>
          <p class="sv-muted">{{ m.reason }}</p>
          <button class="sv-btn sm" @click="startFollow(m.exerciseId)">开始跟练</button>
        </div>
        <div v-if="plan.psyEducation" class="edu">
          <b>{{ plan.psyEducation.topic }}</b>
          <p class="sv-muted">{{ plan.psyEducation.content }}</p>
        </div>
        <SvDisclaimer class="disc" text="方案由 AI 基于你的记录生成，是自助练习参考；练习请量力而行。" />
      </SvCard>

      <!-- ② 跟练模式 -->
      <SvCard v-if="activeEx" id="sv-follow">
        <h3 class="t">{{ activeEx.name }}
          <small class="sv-muted">约 {{ activeEx.durationMin }} 分钟</small></h3>
        <div v-for="(s, i) in activeEx.steps" :key="i" class="step" :class="{ on: doneSteps[i] }">
          <button class="step-btn" @click="doneSteps[i] = !doneSteps[i]"
            :aria-pressed="doneSteps[i]">
            <SvCheckbox :model-value="doneSteps[i]" :label="s.step" />
            <span><b>{{ s.step }}</b><p class="sv-muted">{{ s.desc }}</p></span>
          </button>
        </div>
        <p v-if="checkinMsg" class="okmsg">{{ checkinMsg }}</p>
        <div class="btns">
          <button class="sv-btn" :disabled="doneSteps.some((d) => !d)" @click="checkin(true)">完成并打卡</button>
          <button class="sv-btn ghost" @click="checkin(false)">今天先不做</button>
          <button class="sv-btn plain" @click="active = null">收起</button>
        </div>
      </SvCard>

      <!-- ③ 练习库 -->
      <SvCard>
        <h3 class="t">练习库</h3>
        <p v-if="loading" class="sv-muted">加载中…</p>
        <div v-else class="lib">
          <div v-for="e in exercises" :key="e.id" class="lib-item">
            <div>
              <b>{{ e.name }}</b>
              <small>{{ e.durationMin }} 分钟 · {{ e.steps.length }} 步</small>
            </div>
            <button class="sv-btn ghost sm" @click="startFollow(e.id)">跟练</button>
          </div>
        </div>
      </SvCard>

      <!-- ④ 打卡足迹 -->
      <SvCard>
        <h3 class="t">打卡足迹</h3>
        <p v-if="!records.length" class="sv-muted">还没有记录——哪怕只完成一次呼吸练习，也算数。</p>
        <ul class="recs">
          <li v-for="r in records" :key="r.id">
            <span :class="r.completed ? 'ok' : 'no'">{{ r.completed ? '✓' : '—' }}</span>
            {{ r.exerciseName }}
            <small>{{ fmtTime(r.createdAt) }}</small>
          </li>
        </ul>
      </SvCard>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.hero { display: flex; align-items: center; gap: var(--sv-s3); width: 100%; padding: var(--sv-s4);
  border: none; background: transparent; cursor: pointer; text-align: left; color: var(--sv-label); font-family: inherit; }
.h-ico { display: grid; place-items: center; width: 52px; height: 52px; flex: none; border-radius: 16px;
  background: linear-gradient(140deg, var(--sv-pink), var(--sv-indigo)); color: #fff; }
.h-txt { flex: 1; }
.h-txt b { font-size: var(--sv-fs-callout); display: block; }
.h-txt small { color: var(--sv-label2); font-size: var(--sv-fs-caption1); line-height: 1.5; display: block; margin-top: 2px; }
.t { font-size: var(--sv-fs-title3); margin-bottom: var(--sv-s3); }
.t small { font-size: var(--sv-fs-footnote); font-weight: 400; margin-left: 8px; }
.match { border: 1px solid var(--sv-sep); border-radius: var(--sv-r-ctl); padding: 12px; margin-bottom: var(--sv-s2); }
.match-head { display: flex; justify-content: space-between; align-items: baseline; }
.sched { font-style: normal; font-size: var(--sv-fs-caption1); background: var(--sv-indigo-soft); color: var(--sv-indigo); border-radius: var(--sv-r-pill); padding: 1px 8px; }
.match p { margin: 6px 0 10px; }
.edu { margin-top: var(--sv-s3); background: var(--sv-fill3); border-radius: var(--sv-r-ctl); padding: 12px 14px; }
.edu b { font-size: var(--sv-fs-subhead); }
.edu p { margin-top: 4px; line-height: 1.7; }
.disc { margin-top: var(--sv-s3); }
.step { border-radius: var(--sv-r-ctl); margin-bottom: var(--sv-s2); background: var(--sv-fill3); transition: background .2s; }
.step.on { background: color-mix(in srgb, var(--sv-mint) 12%, var(--sv-card)); }
.step-btn { display: flex; gap: var(--sv-s3); align-items: flex-start; width: 100%; padding: 12px;
  border: none; background: transparent; cursor: pointer; text-align: left; font-family: inherit; color: var(--sv-label); }
.step-btn b { font-size: var(--sv-fs-subhead); }
.step-btn p { margin-top: 3px; }
.okmsg { color: var(--sv-mint); font-size: var(--sv-fs-footnote); margin-bottom: var(--sv-s2); }
.btns { display: flex; gap: var(--sv-s2); flex-wrap: wrap; margin-top: var(--sv-s3); }
.btns .sv-btn { width: auto; flex: 1; justify-content: center; }
.lib { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.lib-item { display: flex; justify-content: space-between; align-items: center; gap: 6px;
  border: 1px solid var(--sv-sep); border-radius: var(--sv-r-ctl); padding: 10px 12px; }
.lib-item b { display: block; font-size: var(--sv-fs-footnote); font-weight: 600; }
.lib-item small { color: var(--sv-label2); font-size: var(--sv-fs-caption2); }
.recs li { padding: 8px 0; border-bottom: 1px dashed var(--sv-sep); font-size: var(--sv-fs-footnote); display: flex; gap: 8px; align-items: baseline; }
.recs li:last-child { border-bottom: none; }
.recs small { color: var(--sv-label3); margin-left: auto; font-variant-numeric: tabular-nums; }
.ok { color: var(--sv-mint); font-weight: 700; } .no { color: var(--sv-label3); }
</style>
