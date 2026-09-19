<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import http, { type ApiResp } from '../api/http'

interface Step { step: string; desc: string }
interface Exercise { id: string; name: string; durationMin: number; applyEmotions: string[]; steps: Step[] }
interface PlanExercise { exerciseId: string; reason: string; schedule: string }
interface Plan { planTitle: string; matchedExercises: PlanExercise[]; psyEducation: { topic: string; content: string } }
interface Record { id: number; exerciseName: string; completed: boolean; feedback: string; createdAt: string }

const exercises = ref<Exercise[]>([])
const plan = ref<Plan | null>(null)
const records = ref<Record[]>([])
const active = ref<string | null>(null)          // 跟练中的练习 id
const doneSteps = ref<boolean[]>([])
const error = ref('')
const checkinMsg = ref('')

const byId = computed(() => Object.fromEntries(exercises.value.map((e) => [e.id, e])))

async function load() {
  const [ex, rec] = await Promise.all([
    http.get<ApiResp<Exercise[]>>('/exercises'),
    http.get<ApiResp<Record[]>>('/exercise-records', { params: { limit: 10 } }),
  ])
  exercises.value = ex.data.data
  records.value = rec.data.data
  // 最新疏导方案（SUPPORT 报告）
  try {
    const { data } = await http.get<ApiResp<{ items: { id: number; type: string }[] }>>('/reports', {
      params: { page: 0, size: 20 },
    })
    const sup = data.data.items.find((i) => i.type === 'SUPPORT')
    if (sup) {
      const detail = await http.get<ApiResp<{ content: Plan }>>(`/reports/${sup.id}`)
      plan.value = detail.data.data.content
    }
  } catch { /* 无方案时仅展示练习库 */ }
}
onMounted(load)

function startFollow(id: string) {
  active.value = id
  doneSteps.value = (byId.value[id]?.steps ?? []).map(() => false)
  checkinMsg.value = ''
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
    error.value = e.message || '打卡失败'
  }
}

const fmtTime = (s: string) => (s ? s.slice(0, 16).replace('T', ' ') : '')
</script>

<template>
  <div class="plan">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>自助练习</b>
    </header>
    <main>
      <p v-if="error" class="err">{{ error }}</p>

      <!-- ① 心屿给的最新方案 -->
      <section v-if="plan" class="card plan-card">
        <h3>🌿 {{ plan.planTitle }}</h3>
        <div v-for="m in plan.matchedExercises" :key="m.exerciseId" class="match">
          <div class="match-head">
            <b>{{ byId[m.exerciseId]?.name || m.exerciseId }}</b>
            <span class="sched">{{ m.schedule }}</span>
          </div>
          <p class="reason">{{ m.reason }}</p>
          <button class="primary sm" @click="startFollow(m.exerciseId)">开始跟练</button>
        </div>
        <div v-if="plan.psyEducation" class="edu">
          <h4>{{ plan.psyEducation.topic }}</h4>
          <p>{{ plan.psyEducation.content }}</p>
        </div>
        <p class="dis">方案由 AI 基于你的记录生成，是自助参考；不构成诊断或治疗建议。</p>
      </section>

      <!-- ② 跟练模式 -->
      <section v-if="active" class="card follow">
        <h3>{{ byId[active]?.name }}（约 {{ byId[active]?.durationMin }} 分钟）</h3>
        <div v-for="(s, i) in byId[active]?.steps" :key="i" class="step" :class="{ on: doneSteps[i] }">
          <label>
            <input type="checkbox" v-model="doneSteps[i]" />
            <div><b>{{ s.step }}</b><p>{{ s.desc }}</p></div>
          </label>
        </div>
        <p v-if="checkinMsg" class="checkin-msg">{{ checkinMsg }}</p>
        <div class="btns">
          <button class="primary" :disabled="doneSteps.some((d) => !d)" @click="checkin(true)">完成并打卡</button>
          <button class="ghost" @click="checkin(false)">今天先不做</button>
          <button class="ghost" @click="active = null">收起</button>
        </div>
      </section>

      <!-- ③ 练习库 -->
      <section class="card">
        <h3>练习库</h3>
        <div class="lib">
          <div v-for="e in exercises" :key="e.id" class="lib-item">
            <div>
              <b>{{ e.name }}</b>
              <small>{{ e.durationMin }} 分钟 · {{ e.steps.length }} 步</small>
            </div>
            <button class="ghost sm" @click="startFollow(e.id)">跟练</button>
          </div>
        </div>
      </section>

      <!-- ④ 打卡足迹 -->
      <section class="card">
        <h3>打卡足迹</h3>
        <div v-if="!records.length" class="sub">还没有记录——哪怕只完成一次呼吸练习，也算数。</div>
        <ul class="recs">
          <li v-for="r in records" :key="r.id">
            <span :class="r.completed ? 'ok' : 'no'">{{ r.completed ? '✓' : '—' }}</span>
            {{ r.exerciseName }}
            <small>{{ fmtTime(r.createdAt) }}</small>
          </li>
        </ul>
      </section>
    </main>
  </div>
</template>

<style scoped>
.plan { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
main { max-width: 720px; margin: 24px auto; padding: 0 16px; }
.card { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); margin-bottom: 16px; }
.card h3 { margin: 0 0 10px; font-size: 16px; color: #40466b; }
.err { color: #d4574e; }
.sub { color: #8a93b5; font-size: 13px; }
.plan-card { border-left: 4px solid #6fd0a8; }
.match { border: 1px solid #e8ecf8; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; }
.match-head { display: flex; justify-content: space-between; align-items: baseline; }
.match-head b { color: #40466b; font-size: 14px; }
.sched { font-size: 12px; color: #5b6cff; background: #eef0ff; padding: 1px 8px; border-radius: 8px; }
.reason { margin: 6px 0 8px; font-size: 13px; color: #7c84a6; }
.primary { padding: 10px 14px; background: #5b6cff; color: #fff; border: 0; border-radius: 10px; font-size: 14px; cursor: pointer; }
.primary:disabled { opacity: .5; cursor: not-allowed; }
.primary.sm, .ghost.sm { padding: 5px 12px; font-size: 13px; }
.ghost { border: 1px solid #dde3f3; background: #fff; border-radius: 10px; padding: 10px 14px; cursor: pointer; color: #5c6483; }
.edu { margin-top: 12px; background: #f8faff; border-radius: 10px; padding: 12px 14px; }
.edu h4 { margin: 0 0 6px; font-size: 14px; color: #40466b; }
.edu p { margin: 0; font-size: 13px; color: #5c6483; line-height: 1.7; }
.dis { font-size: 12px; color: #9aa1bd; margin: 10px 0 0; }
.follow { border-left: 4px solid #5b6cff; }
.step { border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; background: #f8faff; transition: .2s; }
.step.on { background: #e9f8f0; }
.step label { display: flex; gap: 10px; align-items: flex-start; cursor: pointer; }
.step b { color: #40466b; font-size: 14px; }
.step p { margin: 4px 0 0; font-size: 13px; color: #5c6483; }
.checkin-msg { font-size: 13px; color: #2f8a58; }
.btns { display: flex; gap: 10px; margin-top: 10px; }
.lib { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.lib-item { display: flex; justify-content: space-between; align-items: center; border: 1px solid #e8ecf8; border-radius: 10px; padding: 10px 12px; }
.lib-item b { display: block; font-size: 14px; color: #40466b; }
.lib-item small { color: #8a93b5; font-size: 12px; }
.recs { margin: 0; padding-left: 4px; list-style: none; font-size: 13px; color: #40466b; }
.recs li { padding: 6px 0; border-bottom: 1px dashed #eef1fa; display: flex; gap: 8px; align-items: baseline; }
.recs small { color: #9aa1bd; margin-left: auto; }
.ok { color: #2f8a58; font-weight: 700; } .no { color: #b8bfd8; }
</style>
