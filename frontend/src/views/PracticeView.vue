<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import { exerciseApi } from '@/api/domains/exercise'
import { planApi } from '@/api/domains/plan'
import { toast } from '@/stores/ui'
import { useContentStore, type ExerciseDef } from '@/stores/content'
import { businessDate, displayMonthDay } from '@/utils/date'
import type { ActivePlanView, PlanDetailView, PlanItemView } from '@/types/domain'

interface ExRecord {
  id: number
  exerciseName: string
  completed: boolean
  feedback: string
  createdAt: string
}

const router = useRouter()
const content = useContentStore()

const exercises = computed<ExerciseDef[]>(() => content.exercises ?? [])
const plan = ref<ActivePlanView | null>(null)
const allItems = ref<PlanItemView[]>([])
const records = ref<ExRecord[]>([])
const loading = ref(true)
const adapting = ref(false)

async function load() {
  loading.value = true
  try {
    const [, rec, pl] = await Promise.allSettled([
      content.ensureExercises(),
      exerciseApi.records<ExRecord[]>({ limit: 10 }),
      planApi.active<{ plan: ActivePlanView | null }>(),
    ])
    if (rec.status === 'fulfilled') records.value = rec.value
    if (pl.status === 'fulfilled') plan.value = pl.value.plan
    allItems.value = []
    if (plan.value) {
      const planNo = Number(plan.value.planId.replace(/^pl_/, ''))
      const d = await planApi.detail<PlanDetailView>(planNo).catch(() => null)
      allItems.value = d?.items ?? []
    }
  } finally {
    loading.value = false
  }
}
onMounted(() => load().catch(() => toast('加载失败')))

function goFollow(item: PlanItemView) {
  router.push(
    `/practice/room/${item.exerciseId}?planId=${plan.value?.planId || ''}&seq=${item.seq}&scheduled=${item.scheduledDate}`,
  )
}
function goFree(id: string) {
  router.push(`/practice/room/${id}`)
}

const today = () => businessDate()
const dayLabel = (d: string) => (d === today() ? '今天' : displayMonthDay(d))
const fmtTime = (s: string) => (s ? s.slice(0, 16).replace('T', ' ') : '')
const hasTodayUndone = computed(() =>
  allItems.value.some(it => it.scheduledDate === today() && !it.doneAt),
)

async function applyAdaptation() {
  if (!plan.value?.adaptation || adapting.value) return
  adapting.value = true
  try {
    const planNo = Number(plan.value.planId.replace(/^pl_/, ''))
    await planApi.adapt(planNo, plan.value.adaptation.type)
    toast('计划已调整，按新的节奏来')
    await load()
  } catch (e) {
    toast((e as Error).message || '调整失败')
  } finally {
    adapting.value = false
  }
}

async function skipTodayPlan() {
  if (!plan.value || adapting.value) return
  adapting.value = true
  try {
    const planNo = Number(plan.value.planId.replace(/^pl_/, ''))
    await planApi.skipToday(planNo)
    toast('今天不做也算数，计划已顺延')
    await load()
  } catch (e) {
    toast((e as Error).message || '顺延失败')
  } finally {
    adapting.value = false
  }
}
</script>

<template>
  <div class="practice">
    <SvNavBar title="练习" />

    <SvPageBody>
      <!-- 模拟训练入口 -->
      <SvCard :pad="false">
        <button class="hero" @click="router.push('/practice/sim')">
          <span class="h-ico"><SvIcon name="i-heart" :size="26" tone="inherit" /></span>
          <span class="h-txt">
            <b>人际模拟训练</b>
            <small>和「数字人」安全地练一场不好开口的对话 · 多场景 · NVO 逐轮复盘</small>
          </span>
          <SvIcon name="i-arrow" :size="18" tone="label2" />
        </button>
      </SvCard>

      <!-- ① 进行中的小计划（G4） -->
      <SvCard v-if="plan">
        <div class="plan-head">
          <h3 class="t">{{ plan.title }}</h3>
          <span class="sv-cap prog"
            >{{ plan.doneCount }}/{{ plan.totalCount }} · 剩 {{ plan.daysLeft }} 天</span
          >
        </div>
        <div v-for="it in allItems" :key="it.seq" class="pitem" :class="{ on: !!it.doneAt }">
          <div class="pi-left">
            <em class="sched">{{ dayLabel(it.scheduledDate) }}</em>
            <div class="pi-txt">
              <b>{{ it.exerciseName }}</b>
              <small>{{ it.guidance || `约 ${it.durationMin} 分钟` }}</small>
              <small v-if="it.reasons?.length" class="why"
                >为什么是它：{{ it.reasons.join('；') }}</small
              >
            </div>
          </div>
          <button
            v-if="!it.doneAt"
            class="sv-btn sm"
            :disabled="it.scheduledDate > today()"
            @click="goFollow(it)"
          >
            {{ it.scheduledDate === today() ? '去跟练' : '提前做' }}
          </button>
          <span v-else class="ok" aria-label="已完成">✓</span>
        </div>
        <p v-if="plan.todaySkipped" class="sv-muted rest">
          今天休息也算数。下一次安排：{{
            plan.nextScheduledDate ? dayLabel(plan.nextScheduledDate) : '待定'
          }}
        </p>
        <div v-if="plan.adaptation" class="adapt">
          <div class="adapt-txt">
            <b>{{ plan.adaptation.title }}</b>
            <small>{{ plan.adaptation.reason }}</small>
          </div>
          <button class="sv-btn sm" :disabled="adapting" @click="applyAdaptation">
            {{ plan.adaptation.actionLabel }}
          </button>
        </div>
        <div class="plan-foot">
          <button
            v-if="hasTodayUndone"
            class="sv-btn ghost sm"
            :disabled="adapting"
            @click="skipTodayPlan"
          >
            今天不做
          </button>
          <span v-else class="sv-cap">按自己的节奏来就好</span>
        </div>
        <SvDisclaimer
          class="disc"
          text="计划由 AI 基于你的疏导结果生成，是自助练习参考；某一天没做到不算失败。"
        />
      </SvCard>
      <SvCard v-else-if="!loading">
        <h3 class="t">还没有进行中的计划</h3>
        <p class="sv-muted">
          写一篇情绪日记并允许疏导后，心屿会为你排一个几天的小计划。也可以直接从下面练习库挑一个开始。
        </p>
      </SvCard>

      <!-- ② 练习库（自由跟练，C4 沉浸模式） -->
      <SvCard>
        <h3 class="t">练习库</h3>
        <p v-if="loading" class="sv-muted">加载中…</p>
        <p v-else-if="!exercises.length" class="sv-muted">练习库暂时没拉起来，稍后再试。</p>
        <div v-else class="lib">
          <div v-for="e in exercises" :key="e.id" class="lib-item">
            <div>
              <b>{{ e.name }}</b>
              <small>{{ e.durationMin }} 分钟 · {{ e.steps.length }} 步</small>
            </div>
            <button class="sv-btn ghost sm" @click="goFree(e.id)">跟练</button>
          </div>
        </div>
      </SvCard>

      <!-- ③ 打卡足迹 -->
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
    </SvPageBody>
  </div>
</template>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  gap: var(--sv-s3);
  width: 100%;
  padding: var(--sv-s4) var(--sv-s5);
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--sv-label);
  font-family: inherit;
}
.h-ico {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: none;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  background: var(--sv-card2);
  color: var(--sv-accent);
}
.h-txt {
  flex: 1;
}
.h-txt b {
  font-size: var(--sv-fs-body);
  font-weight: 500;
  display: block;
}
.h-txt small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
  line-height: 1.5;
  display: block;
  margin-top: 2px;
}
.t {
  font-size: var(--sv-fs-title3);
  margin-bottom: var(--sv-s3);
}
.plan-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.plan-head .t {
  margin-bottom: 0;
}
.prog {
  color: var(--sv-label2);
  margin-bottom: var(--sv-s3);
  font-variant-numeric: tabular-nums;
}
.pitem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-bottom: var(--sv-s2);
}
.pitem.on {
  background: var(--sv-fill3);
}
.pi-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.sched {
  font-style: normal;
  font-size: var(--sv-fs-caption1);
  border: 1px solid var(--sv-sep);
  color: var(--sv-label2);
  border-radius: var(--sv-r-tag);
  padding: 0 6px;
  flex: none;
}
.pi-txt {
  min-width: 0;
}
.pi-txt b {
  display: block;
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.pi-txt small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
  display: block;
  margin-top: 2px;
}
.pi-txt .why {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
}
.ok {
  color: var(--sv-ok);
  font-weight: 600;
  font-size: 16px;
}
.disc {
  margin-top: var(--sv-s3);
}
.rest {
  margin: 8px 0 0;
}
.adapt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
  margin-top: var(--sv-s3);
}
.adapt-txt {
  min-width: 0;
}
.adapt-txt b {
  display: block;
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.adapt-txt small {
  display: block;
  margin-top: 2px;
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
}
.plan-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: var(--sv-s3);
}
.lib {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.lib-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  padding: 10px 12px;
}
.lib-item b {
  display: block;
  font-size: var(--sv-fs-body);
  font-weight: 500;
}
.lib-item small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
}
.recs li {
  padding: 8px 0;
  border-bottom: 1px solid var(--sv-sep);
  font-size: var(--sv-fs-body);
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.recs li:last-child {
  border-bottom: none;
}
.recs small {
  color: var(--sv-label3);
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.no {
  color: var(--sv-label3);
}
</style>
