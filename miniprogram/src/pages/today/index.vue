<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  activePlan,
  checkIn,
  companionActive,
  makeupCheckIn,
  monthCheckIns,
  notifications,
  streak as fetchStreak,
  todayReading,
  toggleFavorite,
  trajectory,
  type ActivePlan,
  type CheckInInput,
  type CheckInView,
  type ReadingCard,
  type SessionView,
  type StreakView,
} from '@/api/app'
import { readOr } from '@/api/http'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go, goTab } from '@/composables/session'
import { useAuthStore } from '@/stores/auth'
import { clockGreeting, dayOffset, monthOf, todayStr } from '@/utils/date'
import { toast } from '@/utils/feedback'
import { EMOTIONS, emotionByCode } from '@/utils/emotions'

const auth = useAuthStore()
const { themeClass } = useTheme()

const today = todayStr()
const checkin = ref<CheckInView | null>(null)
const streak = ref<StreakView | null>(null)
const plan = ref<ActivePlan | null>(null)
const reading = ref<ReadingCard | null>(null)
const active = ref<SessionView | null>(null)
const unread = ref(0)
const strip = ref<{ date: string; valence: number | null; label: string }[]>([])
const loaded = ref(false)

/** 打卡盘：makeupDate 非空即"补这一天"，面板只有一份，别开两套 UI */
const draft = ref<CheckInInput>({ emotion: '', energy: 3, note: '' })
const makeupDate = ref('')
const saving = ref(false)

const crisis = computed(() => !!auth.me && auth.me.crisisState !== 'NORMAL')
const greeting = computed(() => clockGreeting(auth.me?.nickname))
const todayMeta = computed(() => emotionByCode(checkin.value?.emotionCode))
const draftMeta = computed(() => emotionByCode(draft.value.emotion))
const panelTitle = computed(() =>
  makeupDate.value ? `补记 ${makeupDate.value.slice(5).replace('-', ' 月 ')} 日` : '现在感觉怎么样',
)
/** 一句话只留 200 字：后端同样会拒，端侧先把输入口关住，别让人写完才知道超 */
const noteLeft = computed(() => 200 - (draft.value.note || '').length)

onShow(() => {
  applyTheme()
  if (!ensureSession()) return
  void refresh()
})

async function refresh() {
  await auth.fetchMeOnce(true)
  const month = monthOf()
  const [ci, st, pl, rd, cp, nf, tr] = await Promise.all([
    readOr(monthCheckIns(month)),
    readOr(fetchStreak()),
    readOr(activePlan()),
    readOr(todayReading()),
    readOr(companionActive()),
    readOr(notifications(0, 1)),
    readOr(trajectory(dayOffset(-13), today)),
  ])
  checkin.value = ci?.today ?? null
  streak.value = st
  plan.value = pl?.plan ?? null
  reading.value = rd
  active.value = cp
  unread.value = nf?.unreadCount ?? 0
  strip.value = buildStrip(tr?.points ?? [])
  loaded.value = true
}

/** 近 14 天条：轨迹点按日均值效价上色，空格子才是补签入口 */
function buildStrip(points: { date: string; valence: string; emotion: string }[]) {
  const out: { date: string; valence: number | null; label: string }[] = []
  for (let i = 13; i >= 0; i--) {
    const d = dayOffset(-i)
    const ps = points.filter(p => p.date === d)
    out.push({
      date: d,
      valence: ps.length ? ps.reduce((s, p) => s + Number(p.valence), 0) / ps.length : null,
      label: ps.length ? ps[ps.length - 1].emotion : '',
    })
  }
  return out
}

function stripColor(day: { valence: number | null }) {
  if (day.valence === null) return 'var(--sv-fill2)'
  if (day.valence > 0.25) return 'var(--e-calm)'
  if (day.valence > -0.25) return 'var(--e-bored)'
  return 'var(--e-sad)'
}

function pickStripDay(d: { date: string; valence: number | null }) {
  if (d.date === today || d.valence !== null) return
  makeupDate.value = d.date
  draft.value = { emotion: '', energy: 3, note: '' }
}

function cancelMakeup() {
  makeupDate.value = ''
  draft.value = { emotion: '', energy: 3, note: '' }
}

function choose(code: string) {
  draft.value = { ...draft.value, emotion: code }
}

function editToday() {
  if (!checkin.value) return
  makeupDate.value = ''
  draft.value = {
    emotion: checkin.value.emotionCode,
    energy: checkin.value.energy ?? 3,
    note: checkin.value.note || '',
  }
}

async function submit() {
  if (!draft.value.emotion || saving.value) return
  saving.value = true
  const body: CheckInInput = {
    emotion: draft.value.emotion,
    energy: draft.value.energy || 3,
    note: (draft.value.note || '').trim() || undefined,
  }
  // 补签走 makeup：它才吃"断签不羞辱"的每月一次规则；普通打卡不会误耗名额
  const isMakeup = !!makeupDate.value
  const wasEdit = !isMakeup && !!checkin.value
  try {
    if (isMakeup) await makeupCheckIn(body, makeupDate.value)
    else await checkIn(body)
    draft.value = { emotion: '', energy: 3, note: '' }
    makeupDate.value = ''
    toast(
      isMakeup
        ? '补上了——日历只是提醒你回来'
        : wasEdit
          ? '改好了，今天这条已更新'
          : '记下了，照顾自己的动作值得被记住',
    )
    await refresh()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function saveFavorite() {
  if (!reading.value) return
  try {
    const r = await toggleFavorite(reading.value.kgNodeId)
    reading.value.favorited = r.favorited
    toast(r.favorited ? '已收进成长档案' : '已取消收藏')
  } catch (e) {
    toast((e as Error).message)
  }
}

function openCompanion() {
  goTab('/pages/companion/index')
}
</script>

<template>
  <view class="sv-page today" :class="themeClass">
    <view class="head">
      <view class="head-text">
        <text class="sv-h1">{{ greeting }}</text>
        <text class="sv-muted">{{ today }} · 心屿陪着你</text>
      </view>
      <view class="bell" @tap="go('/pages/notifications/index')">
        <text class="bell-icon">✉</text>
        <text v-if="unread" class="bell-dot">{{ unread > 99 ? '99+' : unread }}</text>
      </view>
    </view>

    <view v-if="crisis" class="crisis" @tap="go('/pages/resources/index')">
      <text class="crisis-title">现在这段最难熬</text>
      <text class="crisis-sub">这里不用等号，点开就能看到可以立刻打通的人 →</text>
    </view>

    <!-- 打卡盘 -->
    <view class="sv-surface panel">
      <view class="row-line">
        <text class="sv-h2">{{ panelTitle }}</text>
        <text v-if="makeupDate" class="link" @tap="cancelMakeup">回到今天</text>
      </view>
      <view class="dial">
        <view
          v-for="e in EMOTIONS"
          :key="e.code"
          class="face"
          :class="{ on: draft.emotion === e.code }"
          @tap="choose(e.code)"
        >
          <view class="face-dot" :style="{ background: 'var(' + e.varName + ')' }">
            <text class="face-glyph">{{ e.face }}</text>
          </view>
          <text class="face-label">{{ e.label }}</text>
        </view>
      </view>

      <view v-if="draft.emotion" class="detail">
        <view class="meter">
          <text class="sv-cap">能量</text>
          <view class="dots">
            <view
              v-for="n in 5"
              :key="n"
              class="dot"
              :class="{ on: (draft.energy || 3) >= n }"
              @tap="draft.energy = n"
            />
          </view>
        </view>
        <textarea
          v-model="draft.note"
          class="sv-field note"
          :maxlength="200"
          placeholder="想多说一句吗？（可以只留情绪）"
          placeholder-class="ph"
        />
        <text class="sv-cap counter">{{ noteLeft }} 字可写</text>
        <button class="sv-btn" :class="{ 'is-disabled': !draftMeta }" @tap="submit">
          {{ makeupDate ? '补上这一天' : checkin ? '记下这一刻' : '就选这个' }}
        </button>
      </view>
      <text v-else class="sv-muted">点一个表情就好，十秒钟的事。</text>

      <view v-if="checkin && !makeupDate" class="done">
        <text class="sv-cap">今天已记录 · {{ todayMeta?.label }}</text>
        <text class="link" @tap="editToday">改一下</text>
      </view>
    </view>

    <!-- 近 14 天 + 连续 -->
    <view class="sv-surface strip-card">
      <view class="row-line">
        <text class="sv-h2">近两周</text>
        <text class="sv-cap">
          连续 {{ streak?.current ?? 0 }} 天 · 最长 {{ streak?.longest ?? 0 }} 天
          {{ streak?.makeupAvailable ? ' · 本月还可补 1 次' : '' }}
        </text>
      </view>
      <scroll-view class="strip" scroll-x>
        <view class="strip-inner">
          <view v-for="d in strip" :key="d.date" class="strip-col" @tap="pickStripDay(d)">
            <view class="strip-dot" :style="{ background: stripColor(d) }" />
            <text class="strip-day">{{ d.date.slice(8) }}</text>
          </view>
        </view>
      </scroll-view>
      <text class="sv-cap hint">点空格子可以补那一天；断签不羞辱，日历只是提醒你回来。</text>
    </view>

    <!-- 树洞续聊胶囊 -->
    <view v-if="active" class="sv-surface capsule" @tap="openCompanion">
      <view>
        <text class="sv-h2">树洞还开着</text>
        <text class="sv-muted">第 {{ active.segmentNo }} 段 · 已聊 {{ active.turns }} 轮</text>
      </view>
      <text class="link">接着说 →</text>
    </view>

    <!-- 今日安排 -->
    <view v-if="plan" class="sv-surface plan">
      <view class="row-line">
        <text class="sv-h2">{{ plan.title }}</text>
        <text class="sv-cap">{{ plan.doneCount }}/{{ plan.totalCount }} · 还剩 {{ plan.daysLeft }} 天</text>
      </view>
      <view v-for="it in plan.items" :key="it.seq" class="plan-item">
        <view class="plan-line">
          <text class="plan-name" :class="{ done: !!it.doneAt }">
            {{ it.scheduledDate.slice(5) }} · {{ it.exerciseName }}
          </text>
          <text class="sv-cap">{{ it.doneAt ? '已完成' : it.durationMin + ' 分钟' }}</text>
        </view>
        <text v-if="it.guidance" class="sv-muted guidance">{{ it.guidance }}</text>
      </view>
      <text class="sv-cap hint">完整的练习室在网页端：这里只把今天该做的排给你看。</text>
    </view>

    <!-- 今日一读 -->
    <view v-if="reading" class="sv-surface reading">
      <view class="row-line">
        <text class="sv-h2">{{ reading.title }}</text>
        <text class="link" @tap="saveFavorite">{{ reading.favorited ? '已收藏' : '收藏' }}</text>
      </view>
      <text class="sv-muted">{{ reading.summary }}</text>
      <view v-if="reading.microAction" class="micro">
        <text class="sv-cap">今天可以试</text>
        <text class="micro-text">{{ reading.microAction }}</text>
      </view>
      <text class="sv-cap hint">约 {{ reading.readingSec }} 秒读完</text>
    </view>

    <view v-if="!loaded" class="sv-surface">
      <text class="sv-muted">正在读取今天…</text>
    </view>
    <view class="tab-spacer" />
  </view>
</template>

<style scoped>
.today {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
}
.head {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--sv-s4);
}
.head-text {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.bell {
  position: relative;
  padding: var(--sv-s2);
}
.bell-icon {
  font-size: 20px;
  color: var(--sv-label2);
}
.bell-dot {
  position: absolute;
  top: 0;
  right: 0;
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--sv-r-pill);
  background: var(--sv-red);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.crisis {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
  margin-bottom: var(--sv-s3);
  padding: var(--sv-s4);
  border-radius: var(--sv-r-card);
  background: rgba(255, 69, 58, 0.12);
}
.crisis-title {
  color: var(--sv-red);
  font-size: var(--sv-fs-headline);
  font-weight: 700;
}
.crisis-sub {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.today > view {
  margin-bottom: var(--sv-s3);
}
.row-line {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sv-s2);
}
.link {
  color: var(--sv-indigo);
  font-size: var(--sv-fs-footnote);
}
.panel {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.dial {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
}
.face {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  width: 25%;
  padding: var(--sv-s2) 0;
  opacity: 0.62;
}
.face.on {
  opacity: 1;
}
.face-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: 3px solid transparent;
  border-radius: 50%;
}
.face.on .face-dot {
  border-color: var(--sv-label);
}
.face-glyph {
  font-size: 22px;
}
.face-label {
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label2);
}
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.meter {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--sv-s3);
}
.dots {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.dot {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--sv-fill2);
}
.dot.on {
  background: var(--sv-indigo);
}
.note {
  min-height: 72px;
  font-size: var(--sv-fs-subhead);
}
.ph {
  color: var(--sv-label3);
}
.counter {
  text-align: right;
}
.done {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--sv-s2);
  border-top: 1px solid var(--sv-sep);
}
.strip-card {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.strip {
  width: 100%;
}
.strip-inner {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.strip-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.strip-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
}
.strip-day {
  font-size: 10px;
  color: var(--sv-label3);
}
.hint {
  color: var(--sv-label3);
  line-height: 1.5;
}
.capsule {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.plan,
.reading {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.plan-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sv-s2) 0;
  border-top: 1px solid var(--sv-sep);
}
.plan-line {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sv-s2);
}
.plan-name {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
}
.plan-name.done {
  color: var(--sv-label3);
  text-decoration: line-through;
}
.guidance {
  line-height: 1.5;
}
.micro {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sv-s2) var(--sv-s3);
  border-radius: var(--sv-r-ctl);
  background: var(--sv-fill3);
}
.micro-text {
  font-size: var(--sv-fs-subhead);
  line-height: 1.5;
}
.tab-spacer {
  height: var(--sv-s4);
}
</style>
