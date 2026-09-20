<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import { riskQueue, riskReveal, riskReview, type RiskRow, type RevealResp } from '@/api/admin'
import { confirmDialog, toast } from '@/stores/ui'

const rows = ref<RiskRow[]>([])
const total = ref(0)
const page = ref(0)
const size = 10
const reviewed = ref<string>('0')
const level = ref('')
const loading = ref(true)

const passwords = reactive<Record<number, string>>({})
const revealed = reactive<Record<number, RevealResp | 'loading'>>({})
const closing = reactive<Record<number, boolean>>({})
const closeCrisis = reactive<Record<number, boolean>>({})

const LEVEL_TONE: Record<string, string> = { CRISIS: 'bad', HIGH: 'bad', MEDIUM: 'warn', LOW: 'run' }

async function load(p = page.value) {
  loading.value = true
  try {
    const r = await riskQueue({
      reviewed: reviewed.value === '' ? undefined : Number(reviewed.value),
      level: level.value || undefined,
      page: p,
      size,
    })
    rows.value = r.items
    total.value = r.total
    page.value = r.page
  } catch (e) {
    toast((e as Error).message || '队列没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function reveal(id: number) {
  const pwd = passwords[id] || ''
  if (!pwd) {
    toast('请输入管理员口令')
    return
  }
  revealed[id] = 'loading'
  try {
    revealed[id] = await riskReveal(id, pwd)
  } catch (e) {
    delete revealed[id]
    toast((e as Error).message || '解密失败')
  }
}

async function review(r: RiskRow) {
  const withCrisis = closeCrisis[r.id] === true
  const ok = await confirmDialog({
    title: '复核结案',
    message: withCrisis
      ? `确认结案 #${r.id} 并解除用户 ${r.userId} 的危机态？解除后将进入冷静期衔接。`
      : `确认标记风险事件 #${r.id} 已复核？（不联动危机状态）`,
    confirmText: '结案',
    danger: withCrisis,
  })
  if (!ok) return
  closing[r.id] = true
  try {
    await riskReview(r.id, withCrisis)
    toast(withCrisis ? '已结案并解除危机' : '已结案')
    delete revealed[r.id]
    load()
  } catch (e) {
    toast((e as Error).message || '操作失败')
  } finally {
    closing[r.id] = false
  }
}

const pages = () => Math.max(1, Math.ceil(total.value / size))
const fmt = (s: string) => (s ? s.replace('T', ' ').slice(0, 19) : '—')
const evidenceText = (ev: RevealResp) => {
  try {
    return JSON.stringify(ev.evidence, null, 2)
  } catch {
    return String(ev.evidence)
  }
}
</script>

<template>
  <AdminShell title="风险复核" subtitle="A2 · 证据解密需二次授权">
    <div class="filters">
      <select v-model="reviewed" @change="load(0)">
        <option value="0">未复核</option>
        <option value="1">已复核</option>
        <option value="">全部</option>
      </select>
      <select v-model="level" @change="load(0)">
        <option value="">全部等级</option>
        <option v-for="l in ['CRISIS', 'HIGH', 'MEDIUM', 'LOW']" :key="l" :value="l">{{ l }}</option>
      </select>
      <span class="muted">{{ total }} 条</span>
    </div>

    <p v-if="loading" class="muted center">加载中…</p>
    <SvCard v-else-if="!rows.length" class="empty">
      <p class="muted center">队列清空了——没有等待复核的风险事件。</p>
    </SvCard>
    <template v-else>
      <SvCard v-for="r in rows" :key="r.id">
        <div class="hd">
          <span class="pill" :class="LEVEL_TONE[r.level] || 'run'">{{ r.level }}</span>
          <b>#{{ r.id }} 用户 {{ r.userId }}</b>
          <small class="muted">{{ fmt(r.createdAt) }}</small>
        </div>
        <p class="meta">
          触发：{{ r.triggerType || '—' }} · 规则 {{ r.ruleCode || '—' }} · 已执行 {{ r.actionTaken || '无' }}
          <template v-if="r.taskId"> · 任务 #{{ r.taskId }}</template>
          · {{ r.reviewed ? '已复核' : '待复核' }}
        </p>

        <div v-if="!r.reviewed" class="ops">
          <template v-if="revealed[r.id] === undefined">
            <span class="pwd-row">
              <input
                v-model="passwords[r.id]"
                type="password"
                class="pwd"
                placeholder="管理员口令（二次授权）"
                autocomplete="off"
                @keyup.enter="reveal(r.id)"
              />
              <button class="btn" @click="reveal(r.id)">查看证据</button>
            </span>
          </template>
          <template v-else-if="revealed[r.id] !== 'loading'">
            <pre class="evi">{{ evidenceText(revealed[r.id] as RevealResp) }}</pre>
          </template>
          <p v-else class="muted">解密中…</p>
          <label class="ck">
            <input v-model="closeCrisis[r.id]" type="checkbox" />
            <span>结案同时解除危机态</span>
          </label>
          <button class="btn primary" :disabled="closing[r.id]" @click="review(r)">
            {{ closing[r.id] ? '提交中…' : '复核结案' }}
          </button>
        </div>
      </SvCard>

      <div class="pager">
        <button class="pg" :disabled="page === 0" @click="load(0)">首页</button>
        <span class="muted">{{ page + 1 }} / {{ pages() }}</span>
        <button class="pg" :disabled="page + 1 >= pages()" @click="load(page + 1)">下一页</button>
      </div>
    </template>
  </AdminShell>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: var(--sv-s4);
}
.filters select {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 10px;
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
  flex-wrap: wrap;
}
.hd b {
  font-size: var(--sv-fs-subhead);
}
.hd small {
  margin-left: auto;
}
.meta {
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
  margin: 8px 0 0;
  line-height: 1.6;
}
.ops {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pwd {
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
.pwd-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.btn {
  border: 1px solid var(--sv-sep);
  background: transparent;
  color: var(--sv-indigo);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  align-self: flex-start;
}
.btn.primary {
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
  color: #fff;
}
.btn:disabled {
  opacity: 0.5;
}
.ck {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label2);
}
.ck input {
  accent-color: var(--sv-indigo);
  width: 16px;
  height: 16px;
}
.evi {
  background: var(--sv-bg);
  border: 1px solid var(--sv-sep);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: var(--sv-fs-caption2);
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 260px;
  overflow-y: auto;
}
.pill {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  flex: none;
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
.pill.run {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
}
[data-theme='dark'] .pill.warn {
  color: #ffd28a;
}
[data-theme='dark'] .pill.bad {
  color: #ff9d9d;
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
