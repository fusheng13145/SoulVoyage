<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import { EMOTIONS } from '@/utils/emotions'
import {
  listGroups,
  createGroup,
  getGroup,
  addGroupMembers,
  removeGroupMember,
  groupStats,
  listUsers,
  type GroupRow,
  type GroupDetail,
  type GroupStats,
  type UserRow,
} from '@/api/admin'
import { confirmDialog, toast } from '@/stores/ui'

const groups = ref<GroupRow[]>([])
const current = ref<GroupDetail | null>(null)
const stats = ref<GroupStats | null>(null)
const days = ref(7)
const loading = ref(true)
const newName = ref('')
const busy = ref(false)

/** 成员检索：用户名搜出候选，勾选后批量加入 */
const searchQ = ref('')
const candidates = ref<UserRow[]>([])

const DAY_OPTIONS = [
  { label: '7 天', value: 7 },
  { label: '14 天', value: 14 },
  { label: '30 天', value: 30 },
]

async function load(keepSelected = true) {
  loading.value = true
  try {
    groups.value = await listGroups()
    if (keepSelected && current.value) {
      const hit = groups.value.find(g => g.id === current.value!.id)
      if (hit) await select(hit)
      else current.value = null
    }
  } catch (e) {
    toast((e as Error).message || '群体列表没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(() => load(false))

async function select(g: GroupRow) {
  try {
    current.value = await getGroup(g.id)
    await loadStats()
  } catch (e) {
    toast((e as Error).message || '群体详情没拉起来')
  }
}

async function loadStats() {
  if (!current.value) return
  try {
    stats.value = await groupStats(current.value.id, days.value)
  } catch (e) {
    toast((e as Error).message || '聚合数据没拉起来')
  }
}

async function pickDays(v: string | number) {
  days.value = Number(v)
  await loadStats()
}

async function create() {
  const n = newName.value.trim()
  if (!n || busy.value) return
  busy.value = true
  try {
    const g = await createGroup(n)
    newName.value = ''
    toast('群体已创建')
    await load(false)
    await select(g)
  } catch (e) {
    toast((e as Error).message || '创建失败')
  } finally {
    busy.value = false
  }
}

async function search() {
  const q = searchQ.value.trim()
  if (!q) {
    candidates.value = []
    return
  }
  try {
    candidates.value = (await listUsers({ q, page: 0, size: 10 })).items
  } catch (e) {
    toast((e as Error).message || '用户检索失败')
  }
}

async function addMember(u: UserRow) {
  if (!current.value) return
  try {
    current.value = await addGroupMembers(current.value.id, [u.id])
    await loadStats()
    toast(`${u.username} 已加入`)
  } catch (e) {
    toast((e as Error).message || '加入失败')
  }
}

async function removeMember(userId: number, username: string) {
  if (!current.value) return
  const ok = await confirmDialog({
    title: '移出群体',
    message: `把「${username}」移出后，其打卡不再参与该群体聚合。确认？`,
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  try {
    await removeGroupMember(current.value.id, userId)
    current.value = await getGroup(current.value.id)
    await loadStats()
  } catch (e) {
    toast((e as Error).message || '移出失败')
  }
}

const emotionLabels = computed(() => {
  const map: Record<string, string> = {}
  for (const e of EMOTIONS) map[e.code.toLowerCase()] = e.label
  return map
})
const maxEmotionCount = computed(() =>
  Math.max(1, ...(stats.value?.checkIns?.emotions.map(e => e.count) ?? [1])),
)
const maxAbsValence = 1
</script>

<template>
  <AdminShell title="群体看板" subtitle="M12 · 只出匿名聚合，授权不足 10 人整体抑制">
    <p v-if="loading" class="muted center">加载中…</p>

    <template v-else>
      <div class="row">
        <input
          v-model="newName"
          class="in"
          maxlength="30"
          placeholder="新群体名（如：2026级心理1班）"
          @keyup.enter="create"
        />
        <button class="btn" :disabled="busy || !newName.trim()" @click="create">建群体</button>
      </div>

      <SvCard
        v-for="g in groups"
        :key="g.id"
        class="pick"
        :class="{ on: current?.id === g.id }"
        @click="select(g)"
      >
        <div class="hd">
          <b>{{ g.name }}</b>
          <span class="pill" :class="g.suppressed ? 'warn' : 'ok'">{{
            g.suppressed ? '看板抑制中' : '可看聚合'
          }}</span>
        </div>
        <p class="meta">成员 {{ g.memberCount }} 人 · 已授权 {{ g.consentedCount }} / 10 人阈值</p>
      </SvCard>
      <p v-if="!groups.length" class="muted center">还没有群体。建一个，再从用户支持页把人拉进来。</p>

      <template v-if="current">
        <h3 class="sec">成员与授权</h3>
        <div class="row">
          <input v-model="searchQ" class="in" placeholder="按用户名检索后加入" @keyup.enter="search" />
          <button class="btn" :disabled="!searchQ.trim()" @click="search">搜</button>
        </div>
        <SvCard v-for="u in candidates" :key="'c' + u.id" class="cand">
          <div class="hd">
            <b>{{ u.username }}</b>
            <span class="meta">#{{ u.id }}</span>
            <button class="btn small" @click="addMember(u)">加入本群体</button>
          </div>
        </SvCard>
        <SvCard>
          <ul class="members">
            <li v-for="m in current.members" :key="m.userId">
              <span>{{ m.username }}</span>
              <span class="pill" :class="m.consented ? 'ok' : 'grey'">{{
                m.consented ? '已授权' : '未授权'
              }}</span>
              <button class="linklike" @click="removeMember(m.userId, m.username)">移出</button>
            </li>
          </ul>
          <p v-if="!current.members.length" class="muted">群体还没有成员。</p>
        </SvCard>

        <h3 class="sec">聚合统计</h3>
        <div class="seg">
          <button
            v-for="o in DAY_OPTIONS"
            :key="o.value"
            class="seg-btn"
            :class="{ on: days === o.value }"
            @click="pickDays(o.value)"
          >
            {{ o.label }}
          </button>
        </div>

        <SvCard v-if="stats?.suppressed" class="notice">
          <b>看板处于抑制状态</b>
          <p class="meta">{{ stats.reason }}</p>
          <p class="meta muted">
            这是产品红线：授权参与群体统计的成员不足 10 人时，任何个体都可能从聚合数里被反推出来。
          </p>
        </SvCard>

        <template v-else-if="stats && stats.checkIns">
          <div class="grid">
            <SvCard class="kpi">
              <p class="num">{{ stats.checkIns.contributors }}</p>
              <p class="cap">参与人数（近 {{ stats.windowDays }} 天有打卡）</p>
            </SvCard>
            <SvCard class="kpi">
              <p class="num">{{ stats.checkIns.personDays }}</p>
              <p class="cap">打卡人次</p>
            </SvCard>
            <SvCard class="kpi">
              <p class="num">{{ stats.checkIns.avgRating ?? '—' }}</p>
              <p class="cap">心情均值（1–5）</p>
            </SvCard>
            <SvCard class="kpi">
              <p class="num">{{ stats.checkIns.avgEnergy ?? '—' }}</p>
              <p class="cap">能量均值（1–5）</p>
            </SvCard>
          </div>

          <SvCard>
            <p class="cap strong">情绪分布（打卡口径）</p>
            <div v-for="e in stats.checkIns.emotions" :key="e.code" class="bar-row">
              <span class="bar-label">{{ emotionLabels[e.code.toLowerCase()] || e.code }}</span>
              <span class="bar" :style="{ width: (e.count / maxEmotionCount) * 100 + '%' }" />
              <span class="bar-num">{{ e.count }}</span>
            </div>
            <p v-if="!stats.checkIns.emotions.length" class="muted">窗口内还没有打卡。</p>
          </SvCard>

          <SvCard>
            <p class="cap strong">效价趋势（仅显示贡献者 ≥3 人的日子）</p>
            <div v-for="d in stats.valenceByDay" :key="d.date" class="bar-row">
              <span class="bar-label">{{ d.date.slice(5) }}</span>
              <span
                class="bar v"
                :style="{
                  width: (Math.abs(d.avgValence) / maxAbsValence) * 50 + '%',
                  marginLeft:
                    d.avgValence >= 0 ? '50%' : 50 - (Math.abs(d.avgValence) / maxAbsValence) * 50 + '%',
                  background: d.avgValence >= 0 ? 'var(--sv-mint)' : 'var(--sv-amber)',
                }"
              />
              <span class="bar-num">{{ d.avgValence.toFixed(2) }}</span>
            </div>
            <p v-if="stats.hiddenDays" class="muted">
              另有 {{ stats.hiddenDays }} 天因贡献者不足 3 人被隐藏——不输出个体可反推的单日数据。
            </p>
            <p v-if="!stats.valenceByDay?.length && !stats.hiddenDays" class="muted">
              窗口内还没有轨迹数据。
            </p>
          </SvCard>

          <SvCard>
            <p class="cap strong">风险事件计数（只有级别，没有个体）</p>
            <p v-if="!Object.keys(stats.riskEvents || {}).length" class="muted">窗口内没有风险事件。</p>
            <div class="ops">
              <span
                v-for="(n, lv) in stats.riskEvents"
                :key="lv"
                class="pill"
                :class="lv === 'HIGH' ? 'bad' : lv === 'MEDIUM' ? 'warn' : 'grey'"
              >
                {{ lv }} × {{ n }}
              </span>
            </div>
          </SvCard>
        </template>
      </template>
    </template>
  </AdminShell>
</template>

<style scoped>
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
}
.row {
  display: flex;
  gap: 8px;
  margin-bottom: var(--sv-s4);
}
.in {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--sv-sep);
  background: var(--sv-bg);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
}
.btn {
  border: 1px solid var(--sv-sep);
  background: transparent;
  color: var(--sv-indigo);
  border-radius: 10px;
  padding: 8px 14px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.btn.small {
  margin-left: auto;
  padding: 6px 10px;
}
.btn:disabled {
  opacity: 0.5;
}
.pick {
  cursor: pointer;
  margin-bottom: var(--sv-s3);
}
.pick.on {
  outline: 2px solid var(--sv-indigo);
}
.hd {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hd b {
  font-size: var(--sv-fs-subhead);
}
.meta {
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
  margin: 6px 0 0;
  line-height: 1.6;
}
.sec {
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  margin: var(--sv-s5) 0 var(--sv-s3);
}
.cand {
  margin-bottom: var(--sv-s3);
}
.members {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: var(--sv-fs-footnote);
}
.members li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid var(--sv-sep);
}
.members li:last-child {
  border-bottom: none;
}
.linklike {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
  cursor: pointer;
  font-family: inherit;
  padding: 6px;
}
.seg {
  display: flex;
  gap: 6px;
  margin-bottom: var(--sv-s4);
}
.seg-btn {
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
}
.seg-btn.on {
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
  color: #fff;
}
.notice {
  background: color-mix(in srgb, var(--sv-amber) 12%, var(--sv-card));
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sv-s3);
  margin-bottom: var(--sv-s3);
}
.kpi .num {
  font-size: var(--sv-fs-title1);
  font-weight: 700;
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.kpi .cap {
  margin: 4px 0 0;
}
.cap {
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
  margin: 0;
}
.cap.strong {
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label);
  font-weight: 600;
  margin-bottom: 10px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 6px 0;
  font-size: var(--sv-fs-caption2);
}
.bar-label {
  width: 44px;
  flex: none;
  color: var(--sv-label2);
}
.bar {
  height: 10px;
  border-radius: 999px;
  background: var(--sv-indigo);
  min-width: 3px;
}
.bar.v {
  max-width: 50%;
  flex: none;
}
.bar-row .bar {
  flex: none;
}
.bar-num {
  color: var(--sv-label3);
  font-variant-numeric: tabular-nums;
}
.ops {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pill {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
}
.pill.ok {
  background: color-mix(in srgb, #30d158 18%, transparent);
  color: #1d7a3a;
}
.pill.warn {
  background: color-mix(in srgb, #ff9f0a 20%, transparent);
  color: #8a5a00;
}
.pill.bad {
  background: color-mix(in srgb, #d64545 16%, transparent);
  color: #b03030;
}
.pill.grey {
  background: var(--sv-indigo-soft);
  color: var(--sv-label2);
}
[data-theme='dark'] .pill.ok {
  color: #6ee787;
}
[data-theme='dark'] .pill.warn {
  color: #ffd28a;
}
[data-theme='dark'] .pill.bad {
  color: #ff9d9d;
}
</style>
