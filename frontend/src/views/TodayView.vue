<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvSheet from '@/components/ui/SvSheet.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvPullToRefresh from '@/components/ui/SvPullToRefresh.vue'
import SvEmotionDial, { type CheckinValue } from '@/components/ui/SvEmotionDial.vue'
import SvCalendarHeat, { type HeatDay } from '@/components/ui/SvCalendarHeat.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import { checkinApi } from '@/api/domains/checkin'
import { isConflict } from '@/api/http'
import { companionApi } from '@/api/domains/companion'
import { emotionApi } from '@/api/domains/emotion'
import { notificationApi } from '@/api/domains/notification'
import { planApi } from '@/api/domains/plan'
import { readingApi } from '@/api/domains/reading'
import { taskApi } from '@/api/domains/task'
import { EMOTIONS } from '@/utils/emotions'
import { PIPELINE_LABELS } from '@/constants/task'
import { addBusinessDays, BUSINESS_TZ, businessDate, businessHour, displayMonthDay } from '@/utils/date'
import type { ActivePlanView, EmotionPointView, PlanItemView } from '@/types/domain'
import { toast } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useCrisisStore } from '@/stores/crisis'

interface CheckInView {
  date: string
  emotionCode: string
  rating: number | null
  energy: number | null
  note: string | null
  madeUp: boolean
  valence: string
}
interface StreakView {
  current: number
  longest: number
  totalDays: number
  makeupAvailable: boolean
}
interface CompanionActive {
  sessionId: number
  segmentNo: number
  status: string
  turns: number
}
interface ReadingView {
  kgNodeId: string
  title: string
  summary: string
  microAction: string
  aboutTags: string[]
  readingSec: number
  date: string
  favorited: boolean
}
interface TaskItem {
  taskNo: string
  pipelineCode: string
  status: string
  createdAt: string
  finishedAt: string
  errorMsg: string | null
}

const router = useRouter()
const auth = useAuthStore()
const crisis = useCrisisStore()

const ptr = ref<InstanceType<typeof SvPullToRefresh> | null>(null)
const days = ref<HeatDay[]>([])
const todayCheckin = ref<CheckInView | null>(null)
const streak = ref<StreakView | null>(null)
const plan = ref<ActivePlanView | null>(null)
const planSaving = ref(false)
const companionActive = ref<CompanionActive | null>(null)
const reading = ref<ReadingView | null>(null)
const tasks = ref<TaskItem[]>([])
const unread = ref(0)
const dial = ref<Partial<CheckinValue>>({})
const saving = ref(false)
const loaded = ref(false)

/* 补签 sheet */
const makeupOpen = ref(false)
const makeupDate = ref('')
const makeupDial = ref<Partial<CheckinValue>>({})

const me = computed(() => auth.me)
const now = ref(new Date())
const greeting = computed(() => {
  const hour = businessHour(now.value)
  return (
    (hour < 5
      ? '还没睡呀'
      : hour < 11
        ? '早上好'
        : hour < 14
          ? '中午好'
          : hour < 18
            ? '下午好'
            : hour < 22
              ? '晚上好'
              : '夜深了') + (me.value?.nickname ? `，${me.value.nickname}` : '')
  )
})

function tick() {
  now.value = new Date()
}

const weekdayFmt = new Intl.DateTimeFormat('zh-CN', { timeZone: BUSINESS_TZ, weekday: 'short' })
const dateLabel = computed(
  () => `${businessDate(now.value).replace(/-/g, '.')} ${weekdayFmt.format(now.value)}`,
)

const today = () => businessDate()

function buildDays(points: EmotionPointView[]): HeatDay[] {
  const out: HeatDay[] = []
  const t = new Date()
  for (let i = 13; i >= 0; i--) {
    const d = new Date(t.getTime() - i * 86400000)
    const key = businessDate(d)
    const ps = points.filter(p => p.date === key)
    out.push(
      ps.length
        ? {
            date: key,
            valence: +(ps.reduce((s, p) => s + parseFloat(p.valence), 0) / ps.length).toFixed(2),
            count: ps.length,
            label: ps[ps.length - 1].emotion,
          }
        : { date: key },
    )
  }
  return out
}

async function load() {
  await auth.fetchMe().catch(() => {
    /* 401 由拦截器处理 */
  })
  try {
    await crisis.refreshProfile()
  } catch {
    /* 画像失败不挡首页 */
  }
  const from = businessDate(addBusinessDays(new Date(), -13))
  const month = today().slice(0, 7)
  const [traj, ci, st, pl, cp, nf, rd, tk] = await Promise.all([
    emotionApi.trajectory<{ points: EmotionPointView[] }>({ from, to: today() }),
    checkinApi.month<{ items: CheckInView[]; today: CheckInView | null }>({ month }),
    checkinApi.streak<StreakView>(),
    planApi.active<{ plan: ActivePlanView | null }>(),
    companionApi.active<CompanionActive | null>(),
    notificationApi.list<{ items: unknown[]; total: number; unreadCount: number }>({
      page: 0,
      size: 1,
    }),
    readingApi.today<ReadingView>().catch(() => null), // 每日一读失败不挡首页
    taskApi.list<{ items: TaskItem[] }>({ page: 0, size: 6 }).catch(() => null), // C5 最近动态
  ])
  days.value = buildDays(traj.points)
  todayCheckin.value = ci.today
  streak.value = st
  plan.value = pl.plan
  companionActive.value = cp
  unread.value = nf.unreadCount
  reading.value = rd ?? null
  tasks.value = tk?.items ?? []
  loaded.value = true
}

async function refresh() {
  try {
    await load()
  } catch {
    toast('刷新失败，稍后再试')
  }
  ptr.value?.done()
}

let clock: ReturnType<typeof setInterval> | undefined
function onVisible() {
  if (document.visibilityState === 'visible') tick()
}
onMounted(() => {
  refresh()
  clock = setInterval(tick, 60_000)
  document.addEventListener('visibilitychange', onVisible)
})
onUnmounted(() => {
  if (clock) clearInterval(clock)
  document.removeEventListener('visibilitychange', onVisible)
})

const todayMeta = computed(
  () => todayCheckin.value && EMOTIONS.find(e => e.code === todayCheckin.value!.emotionCode),
)

async function checkin() {
  if (!dial.value.emotion || saving.value) return
  saving.value = true
  try {
    await checkinApi.create({
      emotion: dial.value.emotion,
      energy: dial.value.energy || 3,
      note: dial.value.note?.trim() || undefined,
    })
    dial.value = {}
    toast('打卡完成，照顾自己的动作值得被记住')
    await load()
  } catch (e) {
    toast((e as Error).message || '打卡失败，稍后再试')
    // B-09：多端并发修改同一打卡 → 拉取最新状态，避免界面停留在旧值
    if (isConflict(e)) await load()
  } finally {
    saving.value = false
  }
}

/* 热力条点空格子 → 补签（14 天内、每月 1 次，后端裁决） */
function pickDay(d: HeatDay) {
  if (d.valence !== undefined || d.date === today()) return
  if (d.date > today()) return
  makeupDate.value = d.date
  makeupDial.value = {}
  makeupOpen.value = true
}

async function submitMakeup() {
  if (!makeupDial.value.emotion || saving.value) return
  saving.value = true
  try {
    await checkinApi.makeup(
      {
        emotion: makeupDial.value.emotion,
        energy: makeupDial.value.energy || 3,
        note: makeupDial.value.note?.trim() || undefined,
      },
      { date: makeupDate.value },
    )
    makeupOpen.value = false
    toast('补上了——断签不羞辱，日历只是提醒你回来')
    await load()
  } catch (e) {
    toast((e as Error).message || '补签失败')
    if (isConflict(e)) await load()
  } finally {
    saving.value = false
  }
}

function goFollow(item: PlanItemView) {
  router.push(
    `/practice/room/${item.exerciseId}?planId=${plan.value?.planId || ''}&seq=${item.seq}&scheduled=${item.scheduledDate}`,
  )
}

const planItemDone = (it: PlanItemView) => !!it.doneAt
const hasTodayUndone = computed(() => plan.value?.items.some(it => !it.doneAt) ?? false)

async function skipTodayPlan() {
  if (!plan.value || planSaving.value) return
  planSaving.value = true
  try {
    const planNo = Number(plan.value.planId.replace(/^pl_/, ''))
    await planApi.skipToday(planNo)
    toast('今天不做也算数，计划已顺延')
    await load()
  } catch (e) {
    toast((e as Error).message || '顺延失败')
  } finally {
    planSaving.value = false
  }
}

/* 最近动态（C5）：任务历史聚合为 feed，用户对系统"做了什么对我"有掌控感 */
const TASK_STATE: Record<string, { word: string; cls: string }> = {
  SUCCESS: { word: '已完成', cls: 'ok' },
  PARTIAL_SUCCESS: { word: '部分完成', cls: 'mid' },
  RUNNING: { word: '进行中', cls: 'run' },
  PENDING: { word: '排队中', cls: 'run' },
  WAITING_USER: { word: '等你确认', cls: 'mid' },
  FAILED: { word: '中断了', cls: 'bad' },
  CANCELLED: { word: '已取消', cls: 'bad' },
}
function feedTime(t: TaskItem): string {
  const iso = t.finishedAt || t.createdAt
  if (!iso) return ''
  const d = new Date(iso)
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/* 每日一读（N3）：纯规则供给，收藏开关 */
async function toggleFavorite() {
  if (!reading.value) return
  try {
    const { favorited } = await readingApi.toggleFavorite({
      type: 'PSY_TOPIC',
      refCode: reading.value.kgNodeId,
    })
    reading.value.favorited = favorited
    toast(favorited ? '已收藏进成长档案' : '已取消收藏')
  } catch (e) {
    toast((e as Error).message || '收藏操作失败')
  }
}
</script>

<template>
  <div class="today">
    <SvNavBar :title="greeting">
      <template #large>
        <p class="date">{{ dateLabel }}</p>
        <h1>{{ greeting }}<small>今天想从哪里开始？</small></h1>
      </template>
      <template #actions>
        <router-link to="/diaries/write" class="nav-act" aria-label="写一篇情绪日记"
          ><SvIcon name="i-pen" :size="20"
        /></router-link>
        <router-link to="/notifications" class="nav-act" aria-label="通知中心">
          <SvIcon name="i-bell" :size="20" />
          <span v-if="unread" class="badge" role="status" :aria-label="`${unread} 条未读`">{{
            unread > 99 ? '99+' : unread
          }}</span>
        </router-link>
      </template>
    </SvNavBar>

    <SvPullToRefresh ref="ptr" @refresh="refresh">
      <SvPageBody>
        <CrisisReferral
          v-if="crisis.crisisMode"
          level="MEDIUM"
          headline="你已在关怀模式中：这段时间不必逼自己「想开点」，专业支持一直在这里"
        />

        <div v-if="me?.status === 3" class="notice">
          账号正在注销冷静期，到期后数据将被不可恢复地销毁。<router-link to="/account"
            >撤回注销 →</router-link
          >
        </div>

        <!-- 心情打卡盘（G1/G3） -->
        <SvCard>
          <div class="dial-head">
            <h2>{{ todayCheckin ? '今天的心情已记下' : '现在，你感觉如何？' }}</h2>
            <span v-if="todayMeta" class="done">
              <i class="edot" :style="{ background: `var(${todayMeta.varName})` }" />{{
                todayMeta.label
              }}</span
            >
          </div>
          <template v-if="!todayCheckin">
            <SvEmotionDial v-model="dial" />
            <button
              class="sv-btn"
              style="margin-top: 16px"
              :disabled="!dial.emotion || saving"
              @click="checkin"
            >
              {{ saving ? '正在写上日历…' : '存进今天' }}
            </button>
          </template>
          <template v-else>
            <p class="sv-muted" style="margin-top: 8px">
              能量 {{ todayCheckin.energy ?? '—' }}/5
              <template v-if="todayCheckin.note"> · 「{{ todayCheckin.note }}」</template>
            </p>
            <p v-if="streak" class="sv-cap streak">
              已为自己记录 {{ streak.totalDays }} 天 · 连续 {{ streak.current }} 天（最长
              {{ streak.longest }} 天）
            </p>
          </template>
          <SvCalendarHeat :days="days" mode="strip" @pick="pickDay" />
          <p v-if="days.some(d => d.valence !== undefined)" class="sv-cap legend">
            <i class="k cool" /><i class="k mid" /><i class="k warm" />低落 → 明亮 · 点空格子可补签（14 天内 ·
            每月 1 次）
          </p>
        </SvCard>

        <!-- 今日小计划（G4 / H-03 自适应） -->
        <SvCard v-if="plan && (plan.items.length || plan.todaySkipped)">
          <div class="dial-head">
            <h2>{{ plan.title }}</h2>
            <span class="sv-cap prog"
              >{{ plan.doneCount }}/{{ plan.totalCount }} · 剩 {{ plan.daysLeft }} 天</span
            >
          </div>
          <div v-for="it in plan.items" :key="it.seq" class="pitem" :class="{ on: planItemDone(it) }">
            <div class="pitem-txt">
              <b>{{ it.exerciseName }}</b>
              <small>{{ it.guidance || `约 ${it.durationMin} 分钟` }}</small>
            </div>
            <button v-if="!planItemDone(it)" class="sv-btn sm" @click="goFollow(it)">去跟练</button>
            <span v-else class="ok" aria-label="已完成">✓</span>
          </div>
          <p v-if="plan.todaySkipped" class="sv-muted rest">
            今天休息也算数。下一次安排：{{
              plan.nextScheduledDate ? displayMonthDay(plan.nextScheduledDate) : '待定'
            }}
          </p>
          <router-link v-if="plan.adaptation" class="adapt-hint" to="/practice">
            {{ plan.adaptation.reason }} 去调整 →
          </router-link>
          <div class="plan-foot">
            <button
              v-if="hasTodayUndone"
              class="sv-btn ghost sm"
              :disabled="planSaving"
              @click="skipTodayPlan"
            >
              今天不做
            </button>
            <router-link class="more" to="/practice">查看全部计划 →</router-link>
          </div>
        </SvCard>

        <!-- 漫聊 / 日记双入口（C0） -->
        <SvCard :pad="false">
          <button class="entry-row" @click="router.push('/companion')">
            <SvIcon name="i-chat" :size="20" tone="inherit" />
            <span class="e-txt">
              <b>来漫聊</b>
              <small>{{
                companionActive
                  ? `接着聊——今天第 ${companionActive.segmentNo} 段，已经说了 ${companionActive.turns} 句`
                  : '不用组织语言，说碎片也可以'
              }}</small>
            </span>
            <SvIcon name="i-arrow" :size="16" tone="label2" />
          </button>
          <button class="entry-row" @click="router.push('/diaries/write')">
            <SvIcon name="i-pen" :size="20" tone="inherit" />
            <span class="e-txt">
              <b>写日记</b>
              <small>让心屿陪你梳理</small>
            </span>
            <SvIcon name="i-arrow" :size="16" tone="label2" />
          </button>
        </SvCard>

        <!-- 每日一读（N3：规则供给不过 LLM，收藏进档案） -->
        <SvCard v-if="reading" :pad="false">
          <details class="fold">
            <summary>
              <span>每日一读 · {{ reading.title }}</span>
              <SvIcon name="i-arrow" :size="14" tone="label2" />
            </summary>
            <div class="rd">
              <div class="rd-main">
                <p class="rd-sum">{{ reading.summary }}</p>
                <p class="rd-micro">微行动：{{ reading.microAction }}</p>
                <p class="sv-cap rd-meta">
                  约 {{ Math.max(1, Math.round(reading.readingSec / 60)) }} 分钟读完
                  <span v-for="t in reading.aboutTags" :key="t" class="rd-tag">{{ t }}</span>
                </p>
              </div>
              <button
                class="rd-star"
                :class="{ on: reading.favorited }"
                :aria-pressed="reading.favorited"
                :aria-label="reading.favorited ? '取消收藏这篇科普' : '收藏这篇科普到成长档案'"
                @click="toggleFavorite"
              >
                <SvIcon name="i-star" :size="18" tone="inherit" />
              </button>
            </div>
          </details>
        </SvCard>

        <!-- 最近动态（C5） -->
        <SvCard v-if="tasks.length" :pad="false">
          <details class="fold">
            <summary>
              <span>最近动态</span>
              <small class="sv-cap">{{ tasks.length }} 条</small>
            </summary>
            <ul class="feed">
              <li v-for="t in tasks" :key="t.taskNo">
                <span class="fd-dot" :class="TASK_STATE[t.status]?.cls || 'run'" aria-hidden="true"></span>
                <span class="fd-txt"
                  >{{ PIPELINE_LABELS[t.pipelineCode] || t.pipelineCode }} ·
                  <b>{{ TASK_STATE[t.status]?.word || t.status }}</b></span
                >
                <small>{{ feedTime(t) }}</small>
              </li>
            </ul>
            <router-link class="fold-more" to="/archive">去档案看产出 →</router-link>
          </details>
        </SvCard>

        <p class="sv-cap foot">写日记让心屿陪你梳理情绪——所有记录信封加密存储，只有你能看到。</p>
      </SvPageBody>
    </SvPullToRefresh>

    <!-- 补签（G3：断签不羞辱） -->
    <SvSheet v-model="makeupOpen" :title="`补一天：${makeupDate.slice(5).replace('-', '/')}`">
      <p class="sv-muted mk-tip">那天发生了什么？选个最接近的情绪就好，一分钟。</p>
      <SvEmotionDial v-model="makeupDial" />
      <button
        class="sv-btn"
        style="margin-top: 14px"
        :disabled="!makeupDial.emotion || saving"
        @click="submitMakeup"
      >
        {{ saving ? '正在写上日历…' : '补进日历' }}
      </button>
    </SvSheet>
  </div>
</template>

<style scoped>
.today {
  min-height: 100%;
}
.date {
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  letter-spacing: 0.4px;
  margin-bottom: 4px;
}
.nav-act {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--sv-accent);
  position: relative;
}
.badge {
  position: absolute;
  top: 6px;
  right: 4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--sv-r-tag);
  background: var(--sv-danger);
  color: var(--sv-on-accent);
  font-size: var(--sv-fs-caption2);
  font-weight: 600;
  display: grid;
  place-items: center;
  line-height: 1;
}
.notice {
  border: 1px solid var(--sv-warn);
  border-radius: var(--sv-r-card);
  padding: 12px 14px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label);
  margin-bottom: 14px;
}
.notice a {
  font-weight: 500;
}
.dial-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: var(--sv-s3);
}
.dial-head h2 {
  font-size: var(--sv-fs-title3);
}
.done {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
}
.edot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.streak {
  margin-top: 6px;
}
.legend {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.k {
  width: 12px;
  height: 12px;
  border-radius: var(--sv-r-tag);
  display: inline-block;
}
.k.cool {
  background: color-mix(in srgb, var(--sv-danger) 55%, var(--sv-card));
}
.k.mid {
  background: color-mix(in srgb, var(--e-joy) 55%, var(--sv-card));
}
.k.warm {
  background: var(--sv-ok);
}
.pitem {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
}
.pitem.on {
  background: var(--sv-fill3);
}
.pitem-txt {
  flex: 1;
  min-width: 0;
}
.pitem-txt b {
  display: block;
  font-size: var(--sv-fs-footnote);
}
.pitem-txt small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
  display: block;
  margin-top: 2px;
}
.pitem .ok {
  color: var(--sv-mint);
  font-weight: 700;
  font-size: 18px;
}
.prog {
  color: var(--sv-label2);
}
.rest {
  margin: 8px 0 0;
}
.adapt-hint {
  display: block;
  margin-top: 8px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-indigo);
  text-decoration: none;
}
.plan-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 10px;
}
.entry-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 56px;
  padding: 14px var(--sv-s5);
  border: none;
  border-bottom: 1px solid var(--sv-sep);
  background: transparent;
  color: var(--sv-label);
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  font-size: var(--sv-fs-body);
}
.entry-row:last-child {
  border-bottom: none;
}
.entry-row > svg:first-child {
  color: var(--sv-accent);
}
.e-txt {
  flex: 1;
  min-width: 0;
}
.e-txt b {
  display: block;
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.e-txt small {
  display: block;
  margin-top: 2px;
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.fold summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px var(--sv-s5);
  cursor: pointer;
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
  list-style: none;
}
.fold summary::-webkit-details-marker {
  display: none;
}
.fold summary svg {
  transition: transform 0.18s ease;
}
.fold[open] summary svg {
  transform: rotate(90deg);
}
.fold .rd {
  padding: 0 var(--sv-s5) var(--sv-s5);
}
.fold .feed {
  padding: 0 var(--sv-s5);
}
.fold-more {
  display: block;
  padding: 10px var(--sv-s5) var(--sv-s5);
  font-size: var(--sv-fs-footnote);
  color: var(--sv-accent);
}
.rd {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.rd-main {
  flex: 1;
  min-width: 0;
}
.rd-sum {
  font-size: var(--sv-fs-footnote);
  line-height: var(--sv-lh-body);
  color: var(--sv-label);
}
.rd-micro {
  margin-top: 8px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-ok);
  line-height: 1.6;
}
.rd-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.rd-tag {
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  color: var(--sv-label2);
}
.rd-star {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: var(--sv-r-ctl);
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  cursor: pointer;
  display: grid;
  place-items: center;
}
.rd-star.on {
  color: var(--sv-warn);
  border-color: var(--sv-warn);
  background: var(--sv-fill3);
}
.more {
  display: inline-block;
  margin-top: 6px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-accent);
}
.mk-tip {
  margin-bottom: 12px;
  line-height: 1.6;
}
.feed {
  list-style: none;
}
.feed li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--sv-sep);
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label);
}
.feed li:last-child {
  border-bottom: none;
}
.feed small {
  margin-left: auto;
  flex: none;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
}
.feed b {
  font-weight: 600;
}
.fd-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.fd-dot.ok {
  background: var(--sv-ok);
}
.fd-dot.mid {
  background: var(--sv-warn);
}
.fd-dot.run {
  background: var(--sv-label3);
}
.fd-dot.bad {
  background: var(--sv-danger);
}
.foot {
  text-align: center;
  padding: var(--sv-s2) 0 var(--sv-s4);
  line-height: 1.6;
}
</style>
