<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import { listUsers, crisisBoard, freezeUser, unfreezeUser, type UserRow } from '@/api/admin'
import { confirmDialog, toast } from '@/stores/ui'

const view = ref<'users' | 'crisis'>('users')
const rows = ref<UserRow[]>([])
const board = ref<UserRow[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const q = ref('')
const status = ref<string>('')
const loading = ref(true)
const busy = ref(0)

const STATUS_LABEL: Record<number, string> = { 1: '正常', 2: '冻结', 3: '注销冷静期' }
const CRISIS_LABEL: Record<string, string> = { CRISIS: '危机中', COOLING: '冷静期', REVIEW: '待复核' }

async function load(p = page.value) {
  loading.value = true
  try {
    if (view.value === 'users') {
      const r = await listUsers({
        q: q.value.trim() || undefined,
        status: status.value ? Number(status.value) : undefined,
        page: p,
        size,
      })
      rows.value = r.items
      total.value = r.total
      page.value = r.page
    } else {
      board.value = await crisisBoard()
    }
  } catch (e) {
    toast((e as Error).message || '用户数据没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)
function switchView(v: 'users' | 'crisis') {
  view.value = v
  load(0)
}

async function freeze(u: UserRow) {
  const ok = await confirmDialog({
    title: '冻结用户',
    message: `冻结「${u.nickname}」(#${u.id}) 后其全部登录会话立即失效，且无法重新登录。确认执行？`,
    confirmText: '冻结',
    danger: true,
  })
  if (!ok) return
  busy.value = u.id
  try {
    await freezeUser(u.id)
    toast('已冻结')
    load()
  } catch (e) {
    toast((e as Error).message || '操作失败')
  } finally {
    busy.value = 0
  }
}

async function unfreeze(u: UserRow) {
  busy.value = u.id
  try {
    await unfreezeUser(u.id)
    toast('已解冻')
    load()
  } catch (e) {
    toast((e as Error).message || '操作失败')
  } finally {
    busy.value = 0
  }
}

const pages = () => Math.max(1, Math.ceil(total.value / size))
const fmt = (s?: string) => (s ? s.replace('T', ' ').slice(0, 19) : '—')
</script>

<template>
  <AdminShell title="用户支持" subtitle="A4 · 只见元数据，密文永不回传">
    <div class="seg">
      <button class="seg-btn" :class="{ on: view === 'users' }" @click="switchView('users')">用户查询</button>
      <button class="seg-btn" :class="{ on: view === 'crisis' }" @click="switchView('crisis')">
        危机看板
      </button>
    </div>

    <div v-if="view === 'users'" class="filters">
      <input v-model="q" class="in" placeholder="用户名 / 昵称" @keyup.enter="load(0)" />
      <select v-model="status" @change="load(0)">
        <option value="">全部状态</option>
        <option value="1">正常</option>
        <option value="2">冻结</option>
        <option value="3">注销冷静期</option>
      </select>
      <button class="btn" @click="load(0)">查</button>
    </div>

    <p v-if="loading" class="muted center">加载中…</p>
    <template v-else-if="view === 'users'">
      <SvCard v-for="u in rows" :key="u.id">
        <div class="hd">
          <b>{{ u.nickname }}</b>
          <span class="pill" :class="u.status === 1 ? 'ok' : u.status === 2 ? 'bad' : 'warn'">{{
            STATUS_LABEL[u.status] || u.status
          }}</span>
          <span v-if="u.role === 'ADMIN'" class="pill role">管理员</span>
        </div>
        <p class="meta">#{{ u.id }} · {{ u.username }} · 注册 {{ fmt(u.createdAt) }}</p>
        <p v-if="u.deletionRequestedAt" class="meta warn-t">
          注销申请于 {{ fmt(u.deletionRequestedAt) }}（冷静期内）
        </p>
        <div class="ops">
          <button v-if="u.status === 1" class="btn danger" :disabled="busy === u.id" @click="freeze(u)">
            冻结
          </button>
          <button v-if="u.status === 2" class="btn" :disabled="busy === u.id" @click="unfreeze(u)">
            解冻
          </button>
        </div>
      </SvCard>
      <p v-if="!rows.length" class="muted center">没有匹配的账户。</p>
      <div v-if="rows.length" class="pager">
        <button class="pg" :disabled="page === 0" @click="load(0)">首页</button>
        <span class="muted">{{ page + 1 }} / {{ pages() }} · 共 {{ total }} 人</span>
        <button class="pg" :disabled="page + 1 >= pages()" @click="load(page + 1)">下一页</button>
      </div>
    </template>

    <template v-else>
      <SvCard v-for="u in board" :key="u.id">
        <div class="hd">
          <b>{{ u.nickname }}</b>
          <span class="pill bad">{{ CRISIS_LABEL[u.crisisState || ''] || u.crisisState }}</span>
        </div>
        <p class="meta">
          #{{ u.id }} · 进入 {{ fmt(u.crisisStartedAt) }} · 预计解除 {{ fmt(u.crisisEndsAt) }}
        </p>
        <ol v-if="u.transitions?.length" class="tr">
          <li v-for="(t, i) in u.transitions" :key="i">
            <span class="muted">{{ fmt(t.at) }}</span> {{ t.from }} → {{ t.to }}
            <em v-if="t.reason">（{{ t.reason }}）</em>
          </li>
        </ol>
      </SvCard>
      <p v-if="!board.length" class="muted center">没有处于危机链路中的用户——这是好消息。</p>
    </template>
  </AdminShell>
</template>

<style scoped>
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
.filters {
  display: flex;
  gap: 8px;
  margin-bottom: var(--sv-s4);
}
.filters .in {
  flex: 1;
  min-width: 0;
}
.filters select {
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 10px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
}
.in {
  border: 1px solid var(--sv-sep);
  background: var(--sv-bg);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
}
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
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
  margin: 8px 0 0;
  line-height: 1.6;
}
.warn-t {
  color: #8a5a00;
}
[data-theme='dark'] .warn-t {
  color: #ffd28a;
}
.ops {
  margin-top: 10px;
  display: flex;
  gap: 8px;
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
}
.btn.danger {
  color: #b03030;
  border-color: color-mix(in srgb, #d64545 40%, transparent);
}
.btn:disabled {
  opacity: 0.5;
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
.pill.role {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
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
.tr {
  margin: 10px 0 0;
  padding-left: 16px;
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
  line-height: 1.8;
}
.tr em {
  font-style: normal;
  color: var(--sv-label3);
}
.pager {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 14px;
  margin: var(--sv-s4) 0;
}
.pg {
  border: none;
  background: transparent;
  color: var(--sv-indigo);
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  padding: 8px;
}
.pg:disabled {
  color: var(--sv-label3);
}
</style>
