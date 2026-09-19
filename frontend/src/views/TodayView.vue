<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvPullToRefresh from '@/components/ui/SvPullToRefresh.vue'
import SvEmotionDial, { type CheckinValue } from '@/components/ui/SvEmotionDial.vue'
import SvCalendarHeat, { type HeatDay } from '@/components/ui/SvCalendarHeat.vue'
import CrisisReferral from '@/components/CrisisReferral.vue'
import http, { type ApiResp } from '@/api/http'
import { EMOTIONS } from '@/utils/emotions'
import { toast } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useCrisisStore } from '@/stores/crisis'

interface TPoint { date: string; sourceType: string; emotion: string; valence: string; intensity: string }

const auth = useAuthStore()
const crisis = useCrisisStore()

const ptr = ref<InstanceType<typeof SvPullToRefresh> | null>(null)
const days = ref<HeatDay[]>([])
const todayRating = ref<TPoint | null>(null)
const dial = ref<Partial<CheckinValue>>({})
const saving = ref(false)
const loaded = ref(false)

const me = computed(() => auth.me)
const hour = new Date().getHours()
const greeting = computed(() =>
  (hour < 5 ? '还没睡呀' : hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : hour < 22 ? '晚上好' : '夜深了')
  + (me.value?.nickname ? `，${me.value.nickname}` : ''))

const today = () => new Date().toLocaleDateString('en-CA')

function buildDays(points: TPoint[]): HeatDay[] {
  const out: HeatDay[] = []
  const t = new Date()
  for (let i = 13; i >= 0; i--) {
    const d = new Date(t.getTime() - i * 86400000)
    const key = d.toLocaleDateString('en-CA')
    const ps = points.filter((p) => p.date === key)
    out.push(ps.length
      ? { date: key, valence: +(ps.reduce((s, p) => s + parseFloat(p.valence), 0) / ps.length).toFixed(2), count: ps.length, label: ps[ps.length - 1].emotion }
      : { date: key })
  }
  return out
}

async function load() {
  await auth.fetchMe().catch(() => { /* 401 由拦截器处理 */ })
  try { await crisis.refreshProfile() } catch { /* 画像失败不挡首页 */ }
  const from = new Date(Date.now() - 13 * 86400000).toLocaleDateString('en-CA')
  const { data } = await http.get<ApiResp<{ points: TPoint[] }>>('/emotions/trajectory', {
    params: { from, to: today() },
  })
  const points = data.data.points
  days.value = buildDays(points)
  todayRating.value = points.filter((p) => p.date === today() && p.sourceType === 'SELF_RATING').pop() ?? null
  loaded.value = true
}

async function refresh() {
  try { await load() } catch { toast('刷新失败，稍后再试') }
  ptr.value?.done()
}
onMounted(refresh)

const todayMeta = computed(() => todayRating.value && EMOTIONS.find((e) => e.label === todayRating.value!.emotion))

async function checkin() {
  if (!dial.value.emotion || saving.value) return
  const meta = EMOTIONS.find((e) => e.code === dial.value.emotion)
  if (!meta) return
  saving.value = true
  try {
    await http.post('/emotions/self-rating', {
      emotion: meta.label,
      valence: meta.valence,
      // 能量 1-5 派生强度锚点：M7 完整打卡表落地前的心跳点
      intensity: +(0.2 + (dial.value.energy || 3) * 0.16).toFixed(2),
      note: dial.value.note?.trim() || undefined,
      date: today(),
    })
    dial.value = {}
    toast('打卡完成，照顾自己的动作值得被记住')
    await load()
  } catch (e: any) {
    toast(e.message || '打卡失败，稍后再试')
  } finally {
    saving.value = false
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
        <router-link to="/diaries/write" class="pen" aria-label="写一篇情绪日记"><SvIcon name="i-pen" :size="20" /></router-link>
      </template>
    </SvNavBar>

    <SvPullToRefresh ref="ptr" @refresh="refresh">
      <div class="body">
        <CrisisReferral v-if="crisis.crisisMode" level="MEDIUM"
          headline="你已在关怀模式中：这段时间不必逼自己「想开点」，专业支持一直在这里" />

        <div v-if="me?.status === 3" class="notice">
          账号正在注销冷静期，到期后数据将被不可恢复地销毁。<router-link to="/account">撤回注销 →</router-link>
        </div>

        <!-- 心情打卡盘 -->
        <SvCard>
          <div class="dial-head">
            <h2>{{ todayRating ? '今天的心情已记下' : '现在，你感觉如何？' }}</h2>
            <span v-if="todayMeta" class="done" :style="{ color: `var(${todayMeta.varName})` }">
              {{ todayMeta.face }} {{ todayMeta.label }}</span>
          </div>
          <template v-if="!todayRating">
            <SvEmotionDial v-model="dial" />
            <button class="sv-btn" style="margin-top: 16px" :disabled="!dial.emotion || saving" @click="checkin">
              {{ saving ? '正在写上日历…' : '存进今天' }}</button>
          </template>
          <p v-else class="sv-muted" style="margin-top: 8px">想再细化一点，可以去日记里让心屿陪你梳理。</p>
          <SvCalendarHeat :days="days" mode="strip" />
          <p class="sv-cap legend" v-if="days.some(d => d.valence !== undefined)">
            <i class="k cool" /><i class="k mid" /><i class="k warm" />低落 → 明亮 · 空格子表示那天没有记录
          </p>
        </SvCard>

        <!-- 漫聊（M7）占位：不伪造功能 -->
        <SvCard :pad="false">
          <div class="manga">
            <span class="m-ico"><SvIcon name="i-chat" :size="24" tone="inherit" /></span>
            <div>
              <b>漫聊 · 随时陪伴</b>
              <p class="sv-muted">低压力聊天空间正在建造中（M7 上线），先用心事日记或练习区陪陪你。</p>
            </div>
          </div>
        </SvCard>

        <SvList title="今天可以做">
          <SvCell label="情绪日记" hint="感知 → 溯源 → 疏导 → 归档" icon="i-pen" tone="var(--sv-indigo-soft)" to="/diaries/write" />
          <SvCell label="日记本" hint="加密时间线 · 回看 编辑 重新分析" icon="i-diary" tone="color-mix(in srgb, var(--e-joy) 20%, transparent)" to="/diaries" />
          <SvCell label="自助练习" hint="正念 · 呼吸 · 认知书写 跟练打卡" icon="i-heart" tone="color-mix(in srgb, var(--sv-mint) 18%, transparent)" to="/practice" />
          <SvCell label="情绪洞察" hint="曲线 · 周画像 · 误区趋势" icon="i-chart" tone="color-mix(in srgb, var(--e-fear) 16%, transparent)" to="/insights" />
        </SvList>

        <p class="sv-cap foot">写日记让心屿陪你梳理情绪——所有记录信封加密存储，只有你能看到。</p>
      </div>
    </SvPullToRefresh>
  </div>
</template>

<style scoped>
.today { min-height: 100%; }
.body { padding: 0 var(--sv-s4); }
.pen { display: grid; place-items: center; width: 44px; height: 44px; color: var(--sv-indigo); }
.notice { background: color-mix(in srgb, var(--sv-amber) 16%, var(--sv-card)); color: var(--sv-label);
  border-radius: var(--sv-r-card); padding: 12px 14px; font-size: var(--sv-fs-footnote); margin-bottom: 14px; }
.notice a { font-weight: 600; }
.dial-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: var(--sv-s3); }
.dial-head h2 { font-size: var(--sv-fs-title3); }
.done { font-size: var(--sv-fs-callout); font-weight: 700; }
.legend { margin-top: 10px; display: flex; align-items: center; gap: 4px; }
.k { width: 12px; height: 12px; border-radius: 4px; display: inline-block; }
.k.cool { background: color-mix(in srgb, var(--sv-red) 62%, var(--sv-card)); }
.k.mid { background: color-mix(in srgb, var(--e-joy) 55%, var(--sv-card)); }
.k.warm { background: var(--sv-mint); }
.manga { display: flex; gap: var(--sv-s3); align-items: center; padding: var(--sv-s4); }
.m-ico { display: grid; place-items: center; width: 48px; height: 48px; flex: none; border-radius: 16px;
  background: linear-gradient(140deg, var(--sv-indigo), var(--sv-purple)); color: #fff; }
.manga b { font-size: var(--sv-fs-subhead); }
.foot { text-align: center; padding: var(--sv-s2) 0 var(--sv-s4); line-height: 1.6; }
</style>
