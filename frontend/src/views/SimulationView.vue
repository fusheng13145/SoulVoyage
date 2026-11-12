<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvBubble from '@/components/ui/SvBubble.vue'
import SvChip from '@/components/ui/SvChip.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvTimeline, { type FlowStep } from '@/components/ui/SvTimeline.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import type { Json } from '@/api/http'
import { simulationApi } from '@/api/domains/simulation'
import { postSse, streamTask } from '@/api/sse'
import { confirmDialog, toast } from '@/stores/ui'
import { useContentStore, type Scene } from '@/stores/content'
import { useCrisisStore } from '@/stores/crisis'

const content = useContentStore()
const crisisStore = useCrisisStore()

const stage = ref<'scenes' | 'chat' | 'review'>('scenes')
const error = ref('')

// —— 场景选择（N1：标签筛选 + 画像推荐）——
const scenes = computed(() => content.scenes ?? [])
const picked = ref<Record<string, string>>({})
const activeTag = ref<string | null>(null)
const DIFFS: Record<string, string> = { MILD: '温和', NORMAL: '普通', HARD: '强硬' }

const allTags = computed(() => {
  const set = new Set<string>()
  scenes.value.forEach(s => (s.tags ?? []).forEach(t => set.add(t)))
  return [...set]
})

/** 近 4 周压力源 Top3：与场景 recommendedFor 求交集即"根据你的档案推荐" */
const topStressors = computed(() => {
  const m = new Map<string, number>()
  crisisStore.weeks
    .slice(-4)
    .forEach(w => w.stressorTop?.forEach(s => m.set(s.name, (m.get(s.name) || 0) + s.count)))
  return [...m.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(e => e[0])
})

const recommended = computed(
  () =>
    new Set(
      scenes.value
        .filter(s => (s.recommendedFor ?? []).some(t => topStressors.value.includes(t)))
        .map(s => s.code),
    ),
)

const visibleScenes = computed(() => {
  const list = activeTag.value
    ? scenes.value.filter(s => s.tags?.includes(activeTag.value!))
    : [...scenes.value]
  return list.sort((a, b) => Number(recommended.value.has(b.code)) - Number(recommended.value.has(a.code)))
})

onMounted(() => {
  loadExtras()
  if (!crisisStore.weeks.length)
    crisisStore.refreshProfile().catch(() => {
      /* 无画像则不推荐 */
    })
})

/* C3：上次没练完 + 历史 best 分 */
interface SimItem {
  simulateId: number
  sceneCode: string
  sceneTitle: string
  status: string
  totalTurns: number
}
interface SimStat {
  sceneCode: string
  bestScore?: number | null
  lastDimensions?: Record<string, number>
}
const unfinished = ref<SimItem[]>([])
const statsMap = ref<Record<string, SimStat>>({})

async function loadExtras() {
  try {
    const data = await simulationApi.list<{ items: SimItem[] }>({ page: 0, size: 5 })
    unfinished.value = data.items
  } catch {
    /* 列表失败只影响续练提示 */
  }
  try {
    const stats = await simulationApi.stats<SimStat[]>()
    statsMap.value = Object.fromEntries(stats.map(s => [s.sceneCode, s]))
  } catch {
    /* 无历史则不展示 best */
  }
}

/** 训练会话（POST /simulations 打开 与 GET /simulations/{id} 续练 的并集视图） */
interface SimSession {
  simulateId: number
  sceneCode?: string
  title?: string
  npcName?: string
  openingLine?: string
}
interface SimDetail {
  simulateId: number
  sceneTitle: string
  npcName: string
  turns: { turnNo: number; userText: string; npcText: string; npcEmotion: string }[]
  totalTurns: number
  maxTurns: number
}

async function resume(it: SimItem) {
  error.value = ''
  try {
    const t = await simulationApi.detail<SimDetail>(it.simulateId)
    session.value = { simulateId: t.simulateId, title: t.sceneTitle, npcName: t.npcName }
    msgs.value = t.turns.flatMap(x => [
      { role: 'user' as const, text: x.userText },
      { role: 'npc' as const, text: x.npcText, turnNo: x.turnNo },
    ])
    mood.value = t.turns.length ? t.turns[t.turns.length - 1].npcEmotion : ''
    lastTag.value = ''
    crisis.value = false
    maxReached.value = t.totalTurns >= t.maxTurns
    unfinished.value = unfinished.value.filter(u => u.simulateId !== it.simulateId)
    stage.value = 'chat'
    scrollChat()
  } catch (e) {
    error.value = (e as Error).message || '继续训练失败'
  }
}

onBeforeRouteLeave(async (_to, _from, next) => {
  if (stage.value !== 'chat' || !session.value || crisis.value) return next()
  const ok = await confirmDialog({
    title: '要中途退出吗？',
    message: '已练的对话会留档为「未练完」，随时可以回来接着说；也可以先「结束并复盘」拿评分。',
    confirmText: '退出并留档',
  })
  if (!ok) return next(false)
  await simulationApi.interrupt(session.value.simulateId).catch(() => {
    /* 静默 */
  })
  next()
})

content
  .ensureScenes()
  .then(list => {
    list.forEach(s => {
      picked.value[s.code] = s.difficulties.includes('NORMAL') ? 'NORMAL' : s.difficulties[0]
    })
  })
  .catch(() => {
    error.value = '场景加载失败'
  })

// —— 训练对话 ——
const session = ref<SimSession | null>(null)
const msgs = ref<{ role: 'npc' | 'user'; text: string; turnNo?: number }[]>([])
const draft = ref('')
const streaming = ref(false)
const mood = ref('')
const tension = ref(0)
const lastTag = ref('')
const crisis = ref(false)
const maxReached = ref(false)
const chatBox = ref<HTMLElement | null>(null)

const MOODS: Record<string, { label: string; cls: string }> = {
  NEUTRAL: { label: '中立', cls: 'm-neutral' },
  DISSATISFIED: { label: '不满', cls: 'm-dissat' },
  ESCALATED: { label: '激化', cls: 'm-esc' },
  SOFTENED: { label: '缓和', cls: 'm-soft' },
}
const TAG_LABELS: Record<string, string> = {
  BOUNDARY_SET: '立住了边界',
  CONFLICT_UP: '冲突升级',
  DE_ESCALATION: '主动缓和',
  ACKNOWLEDGED: '确认了对方的话',
  CRISIS_BREAK: '跳出剧情关心你',
}

async function start(s: Scene) {
  error.value = ''
  try {
    const opened = await simulationApi.open<SimSession>({
      sceneCode: s.code,
      difficulty: picked.value[s.code],
    })
    session.value = opened
    msgs.value = [{ role: 'npc', text: opened.openingLine ?? '' }]
    mood.value = ''
    tension.value = 0
    lastTag.value = ''
    crisis.value = false
    maxReached.value = false
    stage.value = 'chat'
  } catch (e) {
    error.value = (e as Error).message || '开场失败'
  }
}

function scrollChat() {
  requestAnimationFrame(() => chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight }))
}

async function send() {
  const t = draft.value.trim()
  const sid = session.value?.simulateId
  if (!t || !sid || streaming.value || crisis.value) return
  draft.value = ''
  msgs.value.push({ role: 'user', text: t })
  msgs.value.push({ role: 'npc', text: '' })
  const idx = msgs.value.length - 1
  streaming.value = true
  error.value = ''
  scrollChat()
  try {
    await postSse(`/simulations/${sid}/turns`, { userText: t }, e => {
      const npc = msgs.value[idx]
      if (e.event === 'npc_delta') {
        npc.text += e.data.text
        scrollChat()
      } else if (e.event === 'turn_done') {
        npc.turnNo = e.data.turnNo
        mood.value = e.data.npcEmotion
        tension.value = e.data.tension
        lastTag.value = e.data.stateTag || ''
        maxReached.value = !!e.data.maxTurnsReached
        // 增量只负责打字体感；危机退出/校验降级的文本以服务端全文收尾
        if (e.data.npcText) npc.text = e.data.npcText
        if (!npc.text) npc.text = '……'
      } else if (e.event === 'crisis') {
        crisis.value = true
      } else if (e.event === 'error') {
        error.value = e.data.msg || '这轮对话失败了'
        msgs.value.splice(idx, 1)
      }
      scrollChat()
    })
  } catch (e) {
    error.value = (e as Error).message || '连接中断'
  } finally {
    streaming.value = false
  }
}

// —— 结束与复盘 ——
const steps = ref<FlowStep[]>([])
const review = ref<Json | null>(null)
const receipt = ref<Json | null>(null)

const DIM_LABELS: Record<string, string> = {
  LISTEN: '倾听',
  BOUNDARY: '边界表达',
  EMPATHY: '共情',
  CONCESSION: '让步策略',
}
const gradeCls = (g: string) => ({ A: 'g-a', B: 'g-b', C: 'g-c', D: 'g-d' })[g] || 'g-c'
const userTurn = (turn: number) => msgs.value.filter(m => m.role === 'user')[turn - 1]?.text ?? ''

function markStep(agent: string, state: FlowStep['state']) {
  const s = steps.value.find(x => x.agent === agent)
  if (s) s.state = state
}

async function finish() {
  if (!session.value) return
  error.value = ''
  stage.value = 'review'
  steps.value = [
    { agent: 'SIMULATE', stepSeq: 1, state: 'pending' },
    { agent: 'RISK_ARCHIVE', stepSeq: 2, state: 'pending' },
  ]
  review.value = null
  receipt.value = null
  try {
    const { taskNo } = await simulationApi.finish(String(session.value.simulateId))
    await streamTask(taskNo, e => {
      if (e.event === 'step_started') markStep(e.data.agent, 'running')
      else if (e.event === 'middle_result') {
        if (e.data.agent === 'SIMULATE') review.value = e.data.payload
        else if (e.data.agent === 'RISK_ARCHIVE') receipt.value = e.data.payload
        markStep(e.data.agent, 'done')
      } else if (e.event === 'step_failed') markStep(e.data.agent, 'degraded')
      else if (e.event === 'done' && e.data.status === 'FAILED')
        error.value = '复盘未完成，请稍后在报告中查看'
    })
  } catch (e) {
    error.value = (e as Error).message || '复盘失败'
  }
}

function restart() {
  session.value = null
  review.value = null
  msgs.value = []
  stage.value = 'scenes'
  content.ensureScenes(true).catch(() => {
    /* 保持旧列表 */
  })
  loadExtras()
  toast('换个场景，重新开口')
}
</script>

<template>
  <div class="sim">
    <SvNavBar
      :title="stage === 'scenes' ? '人际模拟' : stage === 'chat' ? session?.title || '训练中' : '复盘报告'"
      back="练习"
      back-to="/practice"
      :large="false"
    />

    <SvPageBody>
      <!-- ① 场景选择 -->
      <template v-if="stage === 'scenes'">
        <p class="tip sv-muted">
          选一个最近让你头疼的人际局面，和「数字人」先练一遍——安全地试错，再回到现实。
        </p>
        <p v-if="error && !scenes.length" class="err" role="alert">{{ error }}</p>
        <!-- C3：上次没练完，继续上次说到第几句 -->
        <SvCard v-if="unfinished.length" :pad="false" class="resume">
          <div v-for="u in unfinished" :key="u.simulateId" class="r-row">
            <div class="r-txt">
              <b>上次没练完 · {{ u.sceneTitle }}</b>
              <small>已经练到第 {{ u.totalTurns }} 轮，接着上次说</small>
            </div>
            <button class="sv-btn sm" @click="resume(u)">继续练</button>
          </div>
        </SvCard>
        <div v-if="allTags.length" class="tagbar" role="group" aria-label="场景标签筛选">
          <SvChip :model-value="!activeTag" @update:model-value="activeTag = null">全部</SvChip>
          <SvChip
            v-for="t in allTags"
            :key="t"
            :model-value="activeTag === t"
            @update:model-value="activeTag = activeTag === t ? null : t"
            >{{ t }}</SvChip
          >
        </div>
        <p v-if="activeTag && !visibleScenes.length" class="sv-muted empty-tip">
          这个标签下暂时没有场景，看看「全部」？
        </p>
        <p v-else-if="topStressors.length && recommended.size" class="rec-note sv-cap">
          <SvIcon name="i-sparkle" :size="14" tone="inherit" /> 已按你近几周的「{{
            topStressors.join('、')
          }}」优先排序
        </p>
        <SvCard v-for="s in visibleScenes" :key="s.code" :pad="false" class="scene">
          <div class="s-head">
            <span class="s-ico"><SvIcon name="i-grid" :size="20" tone="inherit" /></span>
            <div>
              <b>{{ s.title }}</b
              ><small
                >{{ s.npcName }} · 你的{{ s.relation
                }}<template v-if="statsMap[s.code]?.bestScore != null">
                  · 最佳 {{ statsMap[s.code].bestScore }} 分</template
                ></small
              >
            </div>
            <span v-if="recommended.has(s.code)" class="rec-badge">档案推荐</span>
          </div>
          <p class="desc">{{ s.description }}</p>
          <p class="dims sv-cap">
            考察
            <span v-for="d in s.goalDimensions" :key="d" class="dim">{{ DIM_LABELS[d] || d }}</span>
          </p>
          <div class="pick" role="radiogroup" aria-label="难度">
            <SvChip
              v-for="d in s.difficulties"
              :key="d"
              :model-value="picked[s.code] === d"
              @update:model-value="picked[s.code] = d"
              >{{ DIFFS[d] || d }}</SvChip
            >
          </div>
          <div class="s-foot">
            <button class="sv-btn" @click="start(s)">进入场景</button>
          </div>
        </SvCard>
      </template>

      <!-- ② 训练对话 -->
      <template v-else-if="stage === 'chat'">
        <SvCard>
          <div class="status">
            <span class="sv-cap">NPC 情绪</span>
            <span v-if="mood" class="mood" :class="MOODS[mood]?.cls">{{ MOODS[mood]?.label || mood }}</span>
            <span v-else class="mood m-neutral">尚未触发</span>
            <div
              class="track"
              role="meter"
              :aria-valuenow="tension"
              aria-valuemin="0"
              aria-valuemax="100"
              aria-label="紧张度"
            >
              <div
                class="fill"
                :class="tension >= 70 ? 'hot' : tension >= 40 ? 'warm' : 'cool'"
                :style="{ width: tension + '%' }"
              />
            </div>
            <span class="sv-cap tnum">{{ tension }}</span>
          </div>
          <p v-if="lastTag" class="tagchip">{{ TAG_LABELS[lastTag] || lastTag }}</p>
        </SvCard>

        <div ref="chatBox" class="chat sv-scroll" aria-live="polite">
          <SvBubble
            v-for="(m, i) in msgs"
            :key="i"
            :role="m.role === 'npc' ? 'ai' : 'me'"
            :name="m.role === 'npc' ? session?.npcName : undefined"
          >
            {{ m.text
            }}<template v-if="m.turnNo"
              ><small class="turnno sv-cap">第 {{ m.turnNo }} 轮</small></template
            >
          </SvBubble>
          <SvBubble v-if="streaming && !msgs[msgs.length - 1]?.text" role="typing" />
          <SvBubble v-if="maxReached" role="sys">已达本轮场景轮数上限，点「结束并复盘」查看逐轮评分</SvBubble>
        </div>

        <CrisisReferral
          v-if="crisis"
          level="HIGH"
          headline="检测到你可能正处于真实的情绪困境，这比任何练习都重要。本次训练已温和中止。"
        />
        <p v-if="error" class="err" role="alert">{{ error }}</p>

        <div class="input-row">
          <textarea
            v-model="draft"
            rows="2"
            :disabled="streaming || crisis"
            aria-label="你想说的话"
            placeholder="说出你想说的话…（NPC 只能听到你说出口的内容）"
            @keydown.enter.exact.prevent="send"
          ></textarea>
          <div class="input-btns">
            <button
              class="send"
              :disabled="streaming || crisis || !draft.trim()"
              aria-label="发送"
              @click="send"
            >
              <SvIcon name="i-send" :size="20" tone="inherit" />
            </button>
            <button class="send ghost" :disabled="streaming" aria-label="结束并复盘" @click="finish">
              <SvIcon name="i-check" :size="20" tone="inherit" />
            </button>
          </div>
        </div>
        <p v-if="streaming" class="sv-cap typing-hint" role="status">对方正在输入…</p>
      </template>

      <!-- ③ 复盘报告 -->
      <template v-else>
        <SvCard v-if="steps.length">
          <SvTimeline :steps="steps" />
        </SvCard>
        <p v-if="error" class="err" role="alert">{{ error }}</p>

        <SvCard v-if="review">
          <div class="score-line">
            <b class="avg">{{ review.overall?.avgScore ?? '--' }}</b>
            <span class="sv-muted">综合沟通分<br /><small>NVO 四要素</small></span>
          </div>
          <p class="advice">{{ review.overallAdvice }}</p>
          <div class="sw">
            <div>
              <h4>做得好</h4>
              <ul>
                <li v-for="(x, i) in review.overall?.strengths" :key="i">{{ x }}</li>
              </ul>
            </div>
            <div>
              <h4>下次改进</h4>
              <ul>
                <li v-for="(x, i) in review.overall?.weaknesses" :key="i">{{ x }}</li>
              </ul>
            </div>
          </div>

          <h4>逐轮评分</h4>
          <div v-for="ts in review.turnScores" :key="ts.turn" class="tscore">
            <span class="grade" :class="gradeCls(ts.grade)">{{ ts.grade }}</span>
            <b>第 {{ ts.turn }} 轮 · {{ DIM_LABELS[ts.dimension] || ts.dimension }}</b>
            <p class="q sv-muted">「{{ userTurn(ts.turn) }}」</p>
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
              <p class="from sv-muted">原话（第 {{ r.turn }} 轮）：{{ r.original }}</p>
              <p class="to">建议：{{ r.optimized }}</p>
            </div>
          </template>

          <template v-if="review.referenceCaseDetail">
            <h4>类似局面，别人怎么谈</h4>
            <div class="refcase">
              <b>{{ review.referenceCaseDetail.title }}</b>
              <p class="sv-muted">{{ review.referenceCaseDetail.situation }}</p>
              <p class="from">容易火上浇油：{{ review.referenceCaseDetail.unhelpful }}</p>
              <p class="to">更有效的说法：{{ review.referenceCaseDetail.helpful }}</p>
            </div>
          </template>
        </SvCard>

        <CrisisReferral
          v-if="receipt && (receipt.riskLevel === 'HIGH' || receipt.referral?.show)"
          :level="receipt.riskLevel === 'HIGH' ? 'HIGH' : 'MEDIUM'"
          :headline="receipt.referral?.headline"
        />

        <SvDisclaimer
          text="NPC 为 AI 扮演的剧情角色，复盘基于对话记录生成，是自助参考而非评价结论"
          reason="评分对照非暴力沟通（观察-感受-需要-请求）四要素框架，只评「表达方式」，不评你这个人的价值。"
        />

        <div class="fin">
          <button class="sv-btn" @click="restart">再练一个场景</button>
          <button class="sv-btn plain" @click="$router.push('/archive')">去成长档案看历史 →</button>
        </div>
        <div v-if="!review && !error" class="loading sv-muted">
          复盘生成中，心智训练 Agent 正在逐轮看你表现…
        </div>
      </template>

      <p class="sv-cap foot">对话中的 NPC 为 AI 扮演，本内容为自助参考，不构成医学诊断。</p>
    </SvPageBody>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: var(--sv-s3);
  line-height: 1.7;
}
.tagbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: var(--sv-s2);
}
.rec-note {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 var(--sv-s3);
  color: var(--sv-accent);
}
.empty-tip {
  text-align: center;
  padding: var(--sv-s5) 0;
}
.rec-badge {
  margin-left: auto;
  flex: none;
  align-self: flex-start;
  border: 1px solid var(--sv-accent);
  color: var(--sv-accent);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  font-size: var(--sv-fs-caption1);
}
.err {
  color: var(--sv-danger);
  font-size: var(--sv-fs-footnote);
  margin: var(--sv-s2) 0;
}
.scene {
  padding: var(--sv-s5);
}
.s-head {
  display: flex;
  align-items: center;
  gap: var(--sv-s3);
  margin-bottom: var(--sv-s2);
}
.s-ico {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  background: var(--sv-card2);
  color: var(--sv-accent);
}
.s-head b {
  font-size: var(--sv-fs-body);
  font-weight: 500;
  display: block;
}
.s-head small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.desc {
  font-size: var(--sv-fs-footnote);
  line-height: var(--sv-lh-body);
  color: var(--sv-label);
}
.dims {
  margin: var(--sv-s2) 0;
}
.dim {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  margin: 0 0 0 4px;
  color: var(--sv-label2);
}
.pick {
  display: flex;
  gap: 8px;
  margin-bottom: var(--sv-s3);
}
.status {
  display: flex;
  align-items: center;
  gap: var(--sv-s2);
  font-size: var(--sv-fs-footnote);
  flex-wrap: wrap;
}
.mood {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  font-size: var(--sv-fs-caption1);
}
.m-neutral {
  color: var(--sv-label3);
}
.m-dissat {
  color: var(--sv-warn);
  border-color: var(--sv-warn);
}
.m-esc {
  color: var(--sv-danger);
  border-color: var(--sv-danger);
}
.m-soft {
  color: var(--sv-ok);
  border-color: var(--sv-ok);
}
.track {
  flex: 1;
  min-width: 80px;
  height: 4px;
  background: var(--sv-sep);
  border-radius: 2px;
  overflow: hidden;
}
.fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.18s ease;
}
.fill.cool {
  background: var(--sv-ok);
}
.fill.warm {
  background: var(--sv-warn);
}
.fill.hot {
  background: var(--sv-danger);
}
.tnum {
  font-variant-numeric: tabular-nums;
}
.tagchip {
  margin-top: var(--sv-s2);
  display: inline-block;
  border: 1px solid var(--sv-sep);
  color: var(--sv-label2);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  font-size: var(--sv-fs-caption1);
}
.chat {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--sv-bg);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-card);
  padding: var(--sv-s3);
  max-height: 48vh;
  overflow-y: auto;
  margin-bottom: var(--sv-s3);
}
.turnno {
  display: block;
  text-align: right;
  margin-top: 4px;
}
.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
textarea {
  flex: 1;
  border: 1px solid var(--sv-line-strong);
  background: var(--sv-card2);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  font-size: var(--sv-fs-body);
  font-family: inherit;
  resize: none;
  color: var(--sv-label);
  outline: none;
}
textarea:focus {
  border-color: var(--sv-accent);
  box-shadow: 0 0 0 2px var(--sv-accent-soft);
}
.input-btns {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.send {
  width: 40px;
  height: 40px;
  border-radius: var(--sv-r-ctl);
  border: 1px solid var(--sv-accent);
  cursor: pointer;
  display: grid;
  place-items: center;
  background: var(--sv-accent);
  color: var(--sv-on-accent);
}
.send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.send.ghost {
  background: var(--sv-card);
  border-color: var(--sv-sep);
  color: var(--sv-label2);
}
.typing-hint {
  text-align: center;
  margin-top: 6px;
}
.score-line {
  display: flex;
  align-items: baseline;
  gap: var(--sv-s3);
}
.avg {
  font-family: var(--sv-num);
  font-size: 40px;
  font-weight: 600;
  color: var(--sv-accent);
  font-variant-numeric: tabular-nums;
}
.score-line small {
  font-size: var(--sv-fs-caption1);
}
.advice {
  color: var(--sv-label);
  font-size: var(--sv-fs-callout);
  margin: var(--sv-s2) 0 var(--sv-s3);
  line-height: var(--sv-lh-prose);
}
h4 {
  margin: var(--sv-s4) 0 var(--sv-s2);
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label3);
  font-weight: 500;
}
.sw {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sv-s3);
  font-size: var(--sv-fs-caption1);
}
.sw ul {
  padding-left: 16px;
  list-style: disc;
  color: var(--sv-label);
  line-height: var(--sv-lh-body);
  margin-top: 4px;
}
.tscore {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  font-size: var(--sv-fs-footnote);
}
.tscore b {
  margin-left: 6px;
}
.grade {
  display: inline-block;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: var(--sv-r-tag);
  color: var(--sv-on-accent);
  font-weight: 600;
  font-size: var(--sv-fs-caption1);
}
.g-a {
  background: var(--sv-ok);
}
.g-b {
  background: var(--sv-label3);
}
.g-c {
  background: var(--sv-warn);
}
.g-d {
  background: var(--sv-danger);
}
.q {
  margin: 6px 0 2px;
}
.moment {
  font-size: var(--sv-fs-footnote);
  background: var(--sv-fill3);
  border-radius: var(--sv-r-tag);
  padding: 8px 10px;
  margin-bottom: 6px;
}
.moment span {
  color: var(--sv-warn);
  margin-right: 6px;
}
.rewrite {
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  background: var(--sv-fill3);
  font-size: var(--sv-fs-footnote);
}
.rewrite .from {
  text-decoration: line-through;
}
.rewrite .to {
  color: var(--sv-ok);
  margin-top: 4px;
}
.resume {
  padding: var(--sv-s3) var(--sv-s4);
  margin-bottom: var(--sv-s3);
}
.r-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}
.r-txt {
  flex: 1;
  min-width: 0;
}
.r-txt b {
  display: block;
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.r-txt small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.refcase {
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
  background: var(--sv-fill3);
  font-size: var(--sv-fs-footnote);
}
.refcase b {
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.refcase .from {
  color: var(--sv-label2);
  margin-top: 6px;
}
.refcase .to {
  color: var(--sv-ok);
  margin-top: 4px;
}
.fin {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
  margin-top: var(--sv-s4);
}
.loading {
  text-align: center;
  padding: var(--sv-s6) 0;
}
.foot {
  text-align: center;
  margin-top: var(--sv-s4);
}
</style>
