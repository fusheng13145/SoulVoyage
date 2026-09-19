<script setup lang="ts">
import { ref } from 'vue'
import http, { type ApiResp } from '../api/http'
import { postSse, streamTask } from '../api/sse'
import AgentFlowProgress from '../components/AgentFlowProgress.vue'

interface Scene {
  code: string; title: string; description: string; npcName: string; relation: string
  difficulties: string[]; goalDimensions: string[]; maxTurns: number
}
interface Msg { role: 'npc' | 'user'; text: string; turnNo?: number }

const stage = ref<'scenes' | 'chat' | 'review'>('scenes')
const error = ref('')

// —— 场景选择 ——
const scenes = ref<Scene[]>([])
const picked = ref<Record<string, string>>({})
const DIFFS: Record<string, string> = { MILD: '温和', NORMAL: '普通', HARD: '强硬' }

async function loadScenes() {
  try {
    const { data } = await http.get<ApiResp<Scene[]>>('/scenes')
    scenes.value = data.data
    data.data.forEach((s) => { picked.value[s.code] = s.difficulties.includes('NORMAL') ? 'NORMAL' : s.difficulties[0] })
  } catch (e: any) {
    error.value = e.message || '场景加载失败'
  }
}
loadScenes()

// —— 训练对话 ——
const session = ref<any>(null)
const msgs = ref<Msg[]>([])
const draft = ref('')
const streaming = ref(false)
const mood = ref('')
const tension = ref(0)
const lastTag = ref('')
const crisis = ref(false)
const maxReached = ref(false)

const MOODS: Record<string, { label: string; cls: string }> = {
  NEUTRAL: { label: '中立', cls: 'm-neutral' },
  DISSATISFIED: { label: '不满', cls: 'm-dissat' },
  ESCALATED: { label: '激化', cls: 'm-esc' },
  SOFTENED: { label: '缓和', cls: 'm-soft' },
}
const TAG_LABELS: Record<string, string> = {
  BOUNDARY_SET: '立住了边界', CONFLICT_UP: '冲突升级', DE_ESCALATION: '主动缓和',
  ACKNOWLEDGED: '确认了对方的话', CRISIS_BREAK: '跳出剧情关心你',
}

async function start(s: Scene) {
  error.value = ''
  try {
    const { data } = await http.post<ApiResp<any>>('/simulations', {
      sceneCode: s.code, difficulty: picked.value[s.code],
    })
    session.value = data.data
    msgs.value = [{ role: 'npc', text: data.data.openingLine }]
    mood.value = ''; tension.value = 0; lastTag.value = ''
    crisis.value = false; maxReached.value = false
    stage.value = 'chat'
  } catch (e: any) {
    error.value = e.message || '开场失败'
  }
}

async function send() {
  const t = draft.value.trim()
  if (!t || streaming.value || crisis.value) return
  draft.value = ''
  msgs.value.push({ role: 'user', text: t })
  msgs.value.push({ role: 'npc', text: '' })
  const idx = msgs.value.length - 1
  streaming.value = true
  error.value = ''
  try {
    await postSse(`/simulations/${session.value.simulateId}/turns`, { userText: t }, (e) => {
      const npc = msgs.value[idx]
      if (e.event === 'npc_delta') npc.text += e.data.text
      else if (e.event === 'turn_done') {
        npc.turnNo = e.data.turnNo
        mood.value = e.data.npcEmotion
        tension.value = e.data.tension
        lastTag.value = e.data.stateTag || ''
        maxReached.value = !!e.data.maxTurnsReached
        if (!npc.text) npc.text = '……'
      } else if (e.event === 'crisis') {
        crisis.value = true
      } else if (e.event === 'error') {
        error.value = e.data.msg || '这轮对话失败了'
        msgs.value.splice(idx, 1)
      }
    })
  } catch (e: any) {
    error.value = e.message || '连接中断'
  } finally {
    streaming.value = false
  }
}

// —— 结束与复盘 ——
const steps = ref<{ agent: string; stepSeq: number; state: 'pending'|'running'|'done'|'degraded'|'failed' }[]>([])
const review = ref<any>(null)

const DIM_LABELS: Record<string, string> = {
  LISTEN: '倾听', BOUNDARY: '边界表达', EMPATHY: '共情', CONCESSION: '让步策略',
}
const gradeCls = (g: string) => ({ A: 'g-a', B: 'g-b', C: 'g-c', D: 'g-d' }[g] || 'g-c')
const userTurn = (turn: number) =>
  msgs.value.filter((m) => m.role === 'user')[turn - 1]?.text ?? ''

async function finish() {
  if (!session.value) return
  error.value = ''
  stage.value = 'review'
  steps.value = [{ agent: 'SIMULATE', stepSeq: 1, state: 'pending' }]
  review.value = null
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>(
      `/simulations/${session.value.simulateId}/finish`, {})
    await streamTask(data.data.taskNo, (e) => {
      if (e.event === 'step_started') steps.value[0].state = 'running'
      else if (e.event === 'middle_result') {
        review.value = e.data.payload
        steps.value[0].state = 'done'
      } else if (e.event === 'step_failed') steps.value[0].state = 'degraded'
      else if (e.event === 'done' && e.data.status === 'FAILED')
        error.value = '复盘未完成，请稍后在报告中查看'
    })
  } catch (e: any) {
    error.value = e.message || '复盘失败'
  }
}

function restart() {
  session.value = null
  review.value = null
  msgs.value = []
  stage.value = 'scenes'
}
</script>

<template>
  <div class="sim">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>人际模拟训练</b>
      <span v-if="stage === 'chat' && session" class="head-meta">
        {{ session.title }} · {{ DIFFS[session.difficulty] || session.difficulty }}
      </span>
    </header>

    <main>
      <!-- ① 场景选择 -->
      <template v-if="stage === 'scenes'">
        <p class="tip">选一个最近让你头疼的人际局面，和「数字人」先吵一架——安全地练一遍，再回到现实。</p>
        <div class="scene-grid">
          <div v-for="s in scenes" :key="s.code" class="scene">
            <h3>{{ s.title }}</h3>
            <p class="who">{{ s.npcName }}（你的{{ s.relation }}）</p>
            <p class="desc">{{ s.description }}</p>
            <p class="dims">
              考察：<span v-for="d in s.goalDimensions" :key="d" class="dim">{{ DIM_LABELS[d] || d }}</span>
            </p>
            <div class="pick">
              <label v-for="d in s.difficulties" :key="d">
                <input type="radio" :value="d" v-model="picked[s.code]" />{{ DIFFS[d] }}
              </label>
            </div>
            <button class="primary" @click="start(s)">进入场景</button>
          </div>
        </div>
      </template>

      <!-- ② 训练对话 -->
      <template v-else-if="stage === 'chat'">
        <div class="background">{{ session.background }}</div>
        <div class="status">
          <span>NPC 情绪：</span>
          <span v-if="mood" class="mood" :class="MOODS[mood]?.cls">{{ MOODS[mood]?.label || mood }}</span>
          <span v-else class="mood">尚未触发</span>
          <div class="track"><div class="fill" :class="tension >= 70 ? 'hot' : tension >= 40 ? 'warm' : 'cool'"
            :style="{ width: tension + '%' }" /></div>
          <span v-if="lastTag" class="tagchip">{{ TAG_LABELS[lastTag] || lastTag }}</span>
        </div>

        <div class="chat">
          <div v-for="(m, i) in msgs" :key="i" class="bubble-row" :class="m.role">
            <div class="bubble">
              <small v-if="m.role === 'npc' && session">{{ session.npcName }}</small>
              <span>{{ m.text }}</span>
              <small v-if="m.turnNo" class="turnno">第 {{ m.turnNo }} 轮</small>
            </div>
          </div>
          <div v-if="maxReached" class="sysline">已达本轮场景轮数上限，点「结束并复盘」查看逐轮评分。</div>
        </div>

        <p v-if="crisis" class="crisis-card">
          <b>检测到你可能正处于真实的情绪困境，这比任何练习都重要。</b><br />
          心理援助热线 <b>12356</b>（24 小时）· 希望 24 热线 400-161-9995 · 建议联系学校心理中心预约面谈。<br />
          <small>本次训练已温和中止，复盘仍可查看，也随时欢迎回来继续练习。</small>
        </p>
        <p v-if="error" class="err">{{ error }}</p>

        <div class="input-row">
          <textarea v-model="draft" rows="2" :disabled="streaming || crisis"
            placeholder="说出你想说的话…（NPC 只能听到你说出口的内容）"
            @keydown.enter.exact.prevent="send"></textarea>
          <div class="input-btns">
            <button class="primary" :disabled="streaming || crisis || !draft.trim()" @click="send">
              {{ streaming ? '对方输入中…' : '发送' }}
            </button>
            <button class="ghost" :disabled="streaming" @click="finish">结束并复盘</button>
          </div>
        </div>
        <p class="disclaimer">对话中的 NPC 为 AI 扮演的剧情角色，本内容为自助参考，不构成医学诊断。</p>
      </template>

      <!-- ③ 复盘报告 -->
      <template v-else>
        <AgentFlowProgress v-if="steps.length" :steps="steps" class="flow" />
        <p v-if="error" class="err">{{ error }}</p>

        <div v-if="review" class="result">
          <div class="score-line">
            <b class="avg">{{ review.overall?.avgScore ?? '--' }}</b>
            <span>综合沟通分（NVO 四要素）</span>
          </div>
          <p class="advice">{{ review.overallAdvice }}</p>
          <div class="sw">
            <div><h4>做得好</h4><ul><li v-for="(x, i) in review.overall?.strengths" :key="i">{{ x }}</li></ul></div>
            <div><h4>下次改进</h4><ul><li v-for="(x, i) in review.overall?.weaknesses" :key="i">{{ x }}</li></ul></div>
          </div>

          <h4>逐轮评分</h4>
          <div v-for="ts in review.turnScores" :key="ts.turn" class="tscore">
            <span class="grade" :class="gradeCls(ts.grade)">{{ ts.grade }}</span>
            <b>第 {{ ts.turn }} 轮 · {{ DIM_LABELS[ts.dimension] || ts.dimension }}</b>
            <p class="q">「{{ userTurn(ts.turn) }}」</p>
            <p class="cmt">{{ ts.comment }}</p>
          </div>

          <template v-if="review.keyMoments?.length">
            <h4>关键时刻</h4>
            <div v-for="(k, i) in review.keyMoments" :key="i" class="moment">
              <span>{{ k.type }} · 第 {{ k.turn }} 轮</span>「{{ k.quote }}」
            </div>
          </template>

          <template v-if="review.rewriteSuggestions?.length">
            <h4>换个说法（非暴力沟通改写）</h4>
            <div v-for="(r, i) in review.rewriteSuggestions" :key="i" class="rewrite">
              <p class="from">原话（第 {{ r.turn }} 轮）：{{ r.original }}</p>
              <p class="to">建议：{{ r.optimized }}</p>
            </div>
          </template>

          <p class="disclaimer">复盘由 AI 基于对话记录生成，是自助参考而非评价结论；如需专业帮助请拨打 12356。</p>
          <button class="primary" @click="restart">再练一个场景</button>
        </div>
        <div v-else-if="!error" class="loading">复盘生成中，心智训练 Agent 正在逐轮看你表现…</div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.sim { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.head-meta { margin-left: auto; color: #8a93b5; font-size: 13px; }
.back { color: #5b6cff; text-decoration: none; }
main { max-width: 760px; margin: 24px auto; padding: 0 16px; }
.tip { background: #eaf0ff; color: #40508c; border-radius: 10px; padding: 12px 14px; font-size: 14px; }
.scene-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
.scene { background: #fff; border-radius: 12px; padding: 16px; box-shadow: 0 2px 10px rgba(80,90,160,.08); display: flex; flex-direction: column; gap: 8px; }
.scene h3 { margin: 0; }
.who { margin: 0; color: #5b6cff; font-size: 13px; }
.desc { margin: 0; color: #4a5170; font-size: 13px; line-height: 1.6; }
.dims { margin: 0; font-size: 12px; color: #8a93b5; }
.dim { background: #f0f3ff; border-radius: 6px; padding: 1px 6px; margin: 0 3px; color: #40508c; }
.pick { display: flex; gap: 12px; font-size: 13px; color: #5c6483; }
.primary { padding: 10px; background: #5b6cff; color: #fff; border: 0; border-radius: 10px; font-size: 14px; cursor: pointer; }
.primary:disabled { opacity: .55; cursor: not-allowed; }
.ghost { padding: 10px 14px; border: 1px solid #dde3f3; background: #fff; border-radius: 10px; cursor: pointer; }
.err { color: #d4574e; font-size: 14px; }
.background { background: #fff; border-left: 3px solid #5b6cff; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #40466b; margin-bottom: 10px; }
.status { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #5c6483; margin-bottom: 10px; }
.mood { border-radius: 8px; padding: 1px 8px; font-size: 12px; background: #eef1fa; color: #40466b; }
.m-neutral { background: #eef1fa; color: #40466b; } .m-dissat { background: #fdf0dd; color: #b07714; }
.m-esc { background: #fde5e2; color: #c04a3f; } .m-soft { background: #e2f6ea; color: #2f8a58; }
.track { flex: 1; height: 6px; background: #eef1fa; border-radius: 3px; overflow: hidden; max-width: 240px; }
.fill { height: 100%; border-radius: 3px; transition: width .4s; }
.fill.cool { background: #6fd0a8; } .fill.warm { background: #f0c05e; } .fill.hot { background: #e2726b; }
.tagchip { background: #eef0ff; color: #40508c; border-radius: 8px; padding: 1px 8px; font-size: 12px; }
.chat { background: #fff; border-radius: 12px; padding: 14px; box-shadow: 0 2px 10px rgba(80,90,160,.08); display: flex; flex-direction: column; gap: 10px; max-height: 50vh; overflow-y: auto; margin-bottom: 12px; }
.bubble-row { display: flex; } .bubble-row.user { justify-content: flex-end; }
.bubble { max-width: 78%; background: #f0f3ff; color: #40466b; border-radius: 12px 12px 12px 4px; padding: 8px 12px; font-size: 14px; line-height: 1.6; }
.bubble-row.user .bubble { background: #5b6cff; color: #fff; border-radius: 12px 12px 4px 12px; }
.bubble small { display: block; font-size: 11px; opacity: .65; margin-bottom: 2px; }
.turnno { text-align: right; margin-top: 4px; }
.sysline { text-align: center; color: #b07714; font-size: 13px; }
.crisis-card { background: #fdf2f1; border: 1px solid #f3c8c3; color: #8c3f36; border-radius: 10px; padding: 12px 14px; font-size: 14px; line-height: 1.8; }
.input-row { display: flex; gap: 10px; align-items: stretch; }
textarea { flex: 1; border: 1px solid #dde3f3; border-radius: 10px; padding: 10px; font-size: 14px; font-family: inherit; resize: vertical; }
.input-btns { display: flex; flex-direction: column; gap: 8px; }
.disclaimer { font-size: 12px; color: #9aa1bd; margin-top: 12px; }
.result { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); }
.result h4 { margin: 18px 0 8px; color: #40466b; font-size: 14px; }
.flow { margin-bottom: 14px; }
.score-line { display: flex; align-items: baseline; gap: 10px; }
.avg { font-size: 40px; color: #5b6cff; }
.score-line span { color: #8a93b5; font-size: 13px; }
.advice { color: #4a5170; font-size: 14px; }
.sw { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px; color: #40466b; }
.sw ul { margin: 4px 0 0; padding-left: 18px; }
.tscore { border: 1px solid #e8ecf8; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; font-size: 13px; color: #40466b; }
.tscore b { margin-left: 6px; }
.grade { display: inline-block; width: 22px; height: 22px; line-height: 22px; text-align: center; border-radius: 6px; color: #fff; font-weight: 700; font-size: 13px; }
.g-a { background: #34a06a; } .g-b { background: #5b8fd8; } .g-c { background: #d99127; } .g-d { background: #d4574e; }
.q { color: #8a93b5; margin: 6px 0 2px; }
.cmt { margin: 0; }
.moment { font-size: 13px; color: #40466b; background: #fafbff; border-radius: 8px; padding: 8px 10px; margin-bottom: 6px; }
.moment span { color: #b07714; margin-right: 6px; }
.rewrite { border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; background: #f6faf7; font-size: 13px; }
.rewrite .from { margin: 0 0 6px; color: #9aa1bd; text-decoration: line-through; }
.rewrite .to { margin: 0; color: #2f6a4a; }
.loading { text-align: center; color: #8a93b5; padding: 40px 0; }
</style>
