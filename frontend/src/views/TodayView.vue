<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvSheet from '@/components/ui/SvSheet.vue'
import SvPullToRefresh from '@/components/ui/SvPullToRefresh.vue'
import SvEmotionDial, { type CheckinValue } from '@/components/ui/SvEmotionDial.vue'
import SvCalendarHeat, { type HeatDay } from '@/components/ui/SvCalendarHeat.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import http, { type ApiResp } from '@/api/http'
import { EMOTIONS } from '@/utils/emotions'
import { toast } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useCrisisStore } from '@/stores/crisis'

interface TPoint {
  date: string
  sourceType: string
  emotion: string
  valence: string
  intensity: string
}
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
interface PlanItem {
  seq: number
  exerciseId: string
  guidance: string
  scheduledDate: string
  doneAt: string | null
  exerciseName: string
  durationMin: number
}
interface PlanView {
  planId: string
  title: string
  days: number
  daysLeft: number
  doneCount: number
  totalCount: number
  items: PlanItem[]
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
const plan = ref<PlanView | null>(null)
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
const hour = new Date().getHours()
const greeting = computed(
  () =>
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
              : '夜深了') + (me.value?.nickname ? `，${me.value.nickname}` : ''),
)

const today = () => new Date().toLocaleDateString('en-CA')

function buildDays(points: TPoint[]): HeatDay[] {
  const out: HeatDay[] = []
  const t = new Date()
  for (let i = 13; i >= 0; i--) {
    const d = new Date(t.getTime() - i * 86400000)
    const key = d.toLocaleDateString('en-CA')
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
  const from = new Date(Date.now() - 13 * 86400000).toLocaleDateString('en-CA')
  const month = today().slice(0, 7)
  const [traj, ci, st, pl, cp, nf, rd, tk] = await Promise.all([
    http.get<ApiResp<{ points: TPoint[] }>>('/emotions/trajectory', { params: { from, to: today() } }),
    http.get<ApiResp<{ items: CheckInView[]; today: CheckInView | null }>>('/mood-check-ins', {
      params: { month },
    }),
    http.get<ApiResp<StreakView>>('/mood-check-ins/streak'),
    http.get<ApiResp<{ plan: PlanView | null }>>('/plans/active'),
    http.get<ApiResp<CompanionActive | null>>('/companion/active'),
    http.get<ApiResp<{ items: unknown[]; total: number; unreadCount: number }>>('/notifications', {
      params: { page: 0, size: 1 },
    }),
    http.get<ApiResp<ReadingView>>('/readings/today').catch(() => null), // 每日一读失败不挡首页
    http.get<ApiResp<{ items: TaskItem[] }>>('/tasks', { params: { page: 0, size: 6 } }).catch(() => null), // C5 最近动态
  ])
  days.value = buildDays(traj.data.data.points)
  todayCheckin.value = ci.data.data.today
  streak.value = st.data.data
  plan.value = pl.data.data.plan
  companionActive.value = cp.data.data
  unread.value = nf.data.data.unreadCount
  reading.value = rd?.data.data ?? null
  tasks.value = tk?.data.data.items ?? []
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
onMounted(refresh)

const todayMeta = computed(
  () => todayCheckin.value && EMOTIONS.find(e => e.code === todayCheckin.value!.emotionCode),
)

async function checkin() {
  if (!dial.value.emotion || saving.value) return
  saving.value = true
  try {
    await http.post('/mood-check-ins', {
      emotion: dial.value.emotion,
      energy: dial.value.energy || 3,
      note: dial.value.note?.trim() || undefined,
    })
    dial.value = {}
    toast('打卡完成，照顾自己的动作值得被记住')
    await load()
  } catch (e) {
    toast((e as Error).message || '打卡失败，稍后再试')
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
    await http.post(
      '/mood-check-ins/makeup',
      {
        emotion: makeupDial.value.emotion,
        energy: makeupDial.value.energy || 3,
        note: makeupDial.value.note?.trim() || undefined,
      },
      { params: { date: makeupDate.value } },
    )
    makeupOpen.value = false
    toast('补上了——断签不羞辱，日历只是提醒你回来')
    await load()
  } catch (e) {
    toast((e as Error).message || '补签失败')
  } finally {
    saving.value = false
  }
}

function goFollow(item: PlanItem) {
  router.push(
    `/practice/room/${item.exerciseId}?planId=${plan.value?.planId || ''}&seq=${item.seq}&scheduled=${item.scheduledDate}`,
  )
}

const planItemDone = (it: PlanItem) => !!it.doneAt

/* 最近动态（C5）：任务历史聚合为 feed，用户对系统"做了什么对我"有掌控感 */
const PIPE_LABEL: Record<string, string> = {
  DIARY_PIPELINE: '日记梳理',
  COMPANION_PIPELINE: '漫聊段落消化',
  COGNITIVE_PIPELINE: '认知书写追问',
  SIMULATE_PIPELINE: '沟通复盘',
  GROWTH_LETTER_PIPELINE: '成长来信',
}
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
    const { data } = await http.post<ApiResp<{ favorited: boolean }>>('/readings/favorites', {
      type: 'PSY_TOPIC',
      refCode: reading.value.kgNodeId,
    })
    reading.value.favorited = data.data.favorited
    toast(data.data.favorited ? '已收藏进成长档案' : '已取消收藏')
  } catch (e) {
    toast((e as Error).message || '收藏操作失败')
  }
}
</script>

<template>
  <div class="today">
    <SvNavBar :title="greeting">
      <template #large>
        <h1>{{ greeting }}<small>今天也要好好陪自己</small></h1>
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
      <div class="body">
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
            <span v-if="todayMeta" class="done" :style="{ color: `var(${todayMeta.varName})` }">
              {{ todayMeta.face }} {{ todayMeta.label }}</span
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
              🔥 已为自己记录 {{ streak.totalDays }} 天 · 连续 {{ streak.current }} 天（最长
              {{ streak.longest }} 天）
            </p>
          </template>
          <SvCalendarHeat :days="days" mode="strip" @pick="pickDay" />
          <p v-if="days.some(d => d.valence !== undefined)" class="sv-cap legend">
            <i class="k cool" /><i class="k mid" /><i class="k warm" />低落 → 明亮 · 点空格子可补签（14 天内 ·
            每月 1 次）
          </p>
        </SvCard>

        <!-- 今日小计划（G4） -->
        <SvCard v-if="plan && plan.items.length">
          <div class="dial-head">
            <h2>🌿 {{ plan.title }}</h2>
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
          <router-link class="more" to="/practice">查看全部计划 →</router-link>
        </SvCard>

        <!-- 每日一读（N3：规则供给不过 LLM，收藏进档案） -->
        <SvCard v-if="reading">
          <div class="rd">
            <div class="rd-main">
              <h2>每日一读 · {{ reading.title }}</h2>
              <p class="rd-sum">{{ reading.summary }}</p>
              <p class="rd-micro">🌱 微行动：{{ reading.microAction }}</p>
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
              <SvIcon name="i-star" :size="20" tone="inherit" />
            </button>
          </div>
        </SvCard>

        <!-- 漫聊 / 日记双入口（C0） -->
        <SvCard :pad="false">
          <div class="duo">
            <button class="duo-main" @click="router.push('/companion')">
              <span class="m-ico"><SvIcon name="i-chat" :size="24" tone="inherit" /></span>
              <b>来漫聊</b>
              <small>{{
                companionActive
                  ? `接着聊——今天第 ${companionActive.segmentNo} 段，已经说了 ${companionActive.turns} 句`
                  : '不用组织语言，说碎片也可以'
              }}</small>
            </button>
            <button class="duo-side" @click="router.push('/diaries/write')">
              <span class="s-ico"><SvIcon name="i-pen" :size="20" tone="inherit" /></span>
              <b>写日记</b>
              <small>让心屿陪你梳理</small>
            </button>
          </div>
        </SvCard>

        <!-- 最近动态（C5） -->
        <SvCard v-if="tasks.length">
          <div class="dial-head">
            <h2>最近动态</h2>
            <router-link class="more feed-more" to="/archive">去档案看产出 →</router-link>
          </div>
          <ul class="feed">
            <li v-for="t in tasks" :key="t.taskNo">
              <span class="fd-dot" :class="TASK_STATE[t.status]?.cls || 'run'" aria-hidden="true"></span>
              <span class="fd-txt"
                >{{ PIPE_LABEL[t.pipelineCode] || t.pipelineCode }} ·
                <b>{{ TASK_STATE[t.status]?.word || t.status }}</b></span
              >
              <small>{{ feedTime(t) }}</small>
            </li>
          </ul>
        </SvCard>

        <SvList title="今天可以做">
          <SvCell
            label="日记本"
            hint="加密时间线 · 回看 编辑 重新分析"
            icon="i-diary"
            tone="color-mix(in srgb, var(--e-joy) 20%, transparent)"
            to="/diaries"
          />
          <SvCell
            label="自助练习"
            hint="呼吸环 · 54321 · 认知书写 沉浸跟练"
            icon="i-heart"
            tone="color-mix(in srgb, var(--sv-mint) 18%, transparent)"
            to="/practice"
          />
          <SvCell
            label="情绪洞察"
            hint="曲线 · 周画像 · 误区趋势"
            icon="i-chart"
            tone="color-mix(in srgb, var(--e-fear) 16%, transparent)"
            to="/insights"
          />
          <SvCell
            label="成长来信"
            hint="每周一封，写给正在长大的你"
            icon="i-mail"
            tone="var(--sv-indigo-soft)"
            to="/letters"
          />
          <SvCell
            label="成就墙"
            hint="每一枚都来自你照顾自己的时刻"
            icon="i-medal"
            tone="color-mix(in srgb, var(--sv-amber) 18%, transparent)"
            to="/achievements"
          />
        </SvList>

        <p class="sv-cap foot">写日记让心屿陪你梳理情绪——所有记录信封加密存储，只有你能看到。</p>
      </div>
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
.body {
  padding: 0 var(--sv-s4);
}
.nav-act {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--sv-indigo);
  position: relative;
}
.badge {
  position: absolute;
  top: 6px;
  right: 4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--sv-red);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  display: grid;
  place-items: center;
  line-height: 1;
}
.notice {
  background: color-mix(in srgb, var(--sv-amber) 16%, var(--sv-card));
  color: var(--sv-label);
  border-radius: var(--sv-r-card);
  padding: 12px 14px;
  font-size: var(--sv-fs-footnote);
  margin-bottom: 14px;
}
.notice a {
  font-weight: 600;
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
  font-size: var(--sv-fs-callout);
  font-weight: 700;
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
  border-radius: 4px;
  display: inline-block;
}
.k.cool {
  background: color-mix(in srgb, var(--sv-red) 62%, var(--sv-card));
}
.k.mid {
  background: color-mix(in srgb, var(--e-joy) 55%, var(--sv-card));
}
.k.warm {
  background: var(--sv-mint);
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
  background: color-mix(in srgb, var(--sv-mint) 10%, var(--sv-card));
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
.rd {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.rd-main {
  flex: 1;
  min-width: 0;
}
.rd-main h2 {
  font-size: var(--sv-fs-title3);
  margin-bottom: 6px;
}
.rd-sum {
  font-size: var(--sv-fs-footnote);
  line-height: 1.7;
  color: var(--sv-label);
}
.rd-micro {
  margin-top: 8px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-mint);
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
  background: var(--sv-fill2);
  border-radius: 6px;
  padding: 1px 6px;
  color: var(--sv-label2);
}
.rd-star {
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: transform 0.12s var(--sv-ease);
}
.rd-star:active {
  transform: scale(0.9);
}
.rd-star.on {
  color: var(--sv-amber);
  border-color: color-mix(in srgb, var(--sv-amber) 45%, var(--sv-sep));
  background: color-mix(in srgb, var(--sv-amber) 12%, var(--sv-card));
}
.more {
  display: inline-block;
  margin-top: 6px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-indigo);
}
.duo {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 10px;
  padding: var(--sv-s4);
}
.duo button {
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  color: var(--sv-label);
  border-radius: var(--sv-r-ctl);
  padding: 14px;
}
.duo-main {
  background: linear-gradient(140deg, var(--sv-indigo), var(--sv-purple));
  color: #fff;
}
.duo-main b {
  font-size: var(--sv-fs-title3);
  display: block;
  margin-top: 10px;
}
.duo-main small {
  opacity: 0.85;
  display: block;
  margin-top: 4px;
  line-height: 1.5;
}
.duo-side {
  border: 1px solid var(--sv-sep);
}
.duo-side b {
  font-size: var(--sv-fs-callout);
  display: block;
  margin-top: 8px;
}
.duo-side small {
  color: var(--sv-label2);
  display: block;
  margin-top: 3px;
}
.m-ico,
.s-ico {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
}
.m-ico {
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
}
.s-ico {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
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
  padding: 7px 0;
  border-bottom: 1px dashed var(--sv-sep);
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
  background: var(--sv-mint);
}
.fd-dot.mid {
  background: var(--sv-amber);
}
.fd-dot.run {
  background: var(--sv-blue);
}
.fd-dot.bad {
  background: var(--sv-red);
}
.feed-more {
  margin-top: 0;
}
.foot {
  text-align: center;
  padding: var(--sv-s2) 0 var(--sv-s4);
  line-height: 1.6;
}
</style>
