<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import {
  listAudit,
  verifyAudit,
  listKeys,
  listExportRecords,
  type AuditRow,
  type KeyStat,
  type ExportRow,
} from '@/api/admin'
import { toast } from '@/stores/ui'

type Tab = 'audit' | 'keys' | 'exports'
const tab = ref<Tab>('audit')
const loading = ref(true)

const audits = ref<AuditRow[]>([])
const auditTotal = ref(0)
const auditPage = ref(0)
const auditUserId = ref('')
const auditAction = ref('')

const verify = ref<{ intact: boolean; checked: number; brokenAtId: number | null } | null>(null)
const verifying = ref(false)

const keys = ref<KeyStat[]>([])
const exportsList = ref<ExportRow[]>([])
const expTotal = ref(0)
const expPage = ref(0)
const size = 10

async function load(p = auditPage.value) {
  loading.value = true
  try {
    if (tab.value === 'audit') {
      const r = await listAudit({
        userId: auditUserId.value.trim() ? Number(auditUserId.value) : undefined,
        action: auditAction.value.trim() || undefined,
        page: p,
        size,
      })
      audits.value = r.items
      auditTotal.value = r.total
      auditPage.value = r.page
    } else if (tab.value === 'keys') {
      keys.value = await listKeys()
    } else {
      const r = await listExportRecords({ page: expPage.value, size })
      exportsList.value = r.items
      expTotal.value = r.total
    }
  } catch (e) {
    toast((e as Error).message || '数据没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)
function switchTab(t: Tab) {
  tab.value = t
  auditPage.value = 0
  expPage.value = 0
  load(0)
}

function expTo(p: number) {
  expPage.value = p
  load()
}

async function runVerify() {
  verifying.value = true
  try {
    verify.value = await verifyAudit()
  } catch (e) {
    toast((e as Error).message || '校验失败')
  } finally {
    verifying.value = false
  }
}

const pages = (total: number) => Math.max(1, Math.ceil(total / size))
const fmt = (s: string) => (s ? s.replace('T', ' ').slice(0, 19) : '—')
</script>

<template>
  <AdminShell title="审计与密钥" subtitle="A5 · 全只读视图">
    <div class="seg">
      <button class="seg-btn" :class="{ on: tab === 'audit' }" @click="switchTab('audit')">审计日志</button>
      <button class="seg-btn" :class="{ on: tab === 'keys' }" @click="switchTab('keys')">数据密钥</button>
      <button class="seg-btn" :class="{ on: tab === 'exports' }" @click="switchTab('exports')">
        导出留痕
      </button>
    </div>

    <p v-if="loading" class="muted center">加载中…</p>
    <template v-else-if="tab === 'audit'">
      <div class="filters">
        <input v-model="auditUserId" class="in" placeholder="用户 ID" @keyup.enter="load(0)" />
        <input
          v-model="auditAction"
          class="in"
          placeholder="动作（如 ADMIN_FREEZE）"
          @keyup.enter="load(0)"
        />
        <button class="btn" @click="load(0)">查</button>
      </div>
      <SvCard class="bar-card">
        <button class="btn" :disabled="verifying" @click="runVerify">
          {{ verifying ? '重放校验中…' : '完整性校验（全链重放）' }}
        </button>
        <p v-if="verify" class="vf" :class="verify.intact ? 'ok' : 'bad'">
          {{
            verify.intact
              ? `链完整：${verify.checked} 条记录逐条重算通过。`
              : `断链！自 #${verify.brokenAtId} 起重算不符（已核对 ${verify.checked} 条），请立即排查。`
          }}
        </p>
      </SvCard>
      <SvCard :pad="false">
        <div v-for="a in audits" :key="a.id" class="row">
          <b>{{ a.action }}</b>
          <small>用户 #{{ a.userId }} · {{ a.target || '—' }} · {{ a.ip || '—' }}</small>
          <em>{{ fmt(a.createdAt) }}</em>
        </div>
        <p v-if="!audits.length" class="muted center">没有符合条件的审计记录。</p>
      </SvCard>
      <div class="pager">
        <button class="pg" :disabled="auditPage === 0" @click="load(0)">首页</button>
        <span class="muted">{{ auditPage + 1 }} / {{ pages(auditTotal) }} · 共 {{ auditTotal }} 条</span>
        <button class="pg" :disabled="auditPage + 1 >= pages(auditTotal)" @click="load(auditPage + 1)">
          下一页
        </button>
      </div>
    </template>

    <template v-else-if="tab === 'keys'">
      <SvCard :pad="false">
        <table v-if="keys.length">
          <thead>
            <tr>
              <th>状态</th>
              <th>密钥数</th>
              <th>最大版本</th>
              <th>覆盖用户</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="k in keys" :key="k.status">
              <td>{{ k.status }}</td>
              <td>{{ k.keys }}</td>
              <td>{{ k.maxVersion ?? '—' }}</td>
              <td>{{ k.owners }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted center">还没有数据密钥。</p>
      </SvCard>
      <p class="muted note">
        ACTIVE=新写入使用 · DECRYPT_ONLY=只解旧密文 ·
        DESTROYED=已销毁。密钥由信封加密体系自动轮换，此页仅观察。
      </p>
    </template>

    <template v-else>
      <SvCard :pad="false">
        <div v-for="x in exportsList" :key="x.id" class="row">
          <b>{{ x.kind }} · {{ x.status }}</b>
          <small>用户 #{{ x.userId }} · 引用 {{ x.fileRef || '—' }}</small>
          <em
            >生成 {{ fmt(x.createdAt)
            }}<template v-if="x.claimedAt"> · 领取 {{ fmt(x.claimedAt) }}</template></em
          >
        </div>
        <p v-if="!exportsList.length" class="muted center">还没有导出记录。</p>
      </SvCard>
      <div class="pager">
        <button class="pg" :disabled="expPage === 0" @click="expTo(0)">首页</button>
        <span class="muted">{{ expPage + 1 }} / {{ pages(expTotal) }} · 共 {{ expTotal }} 条</span>
        <button class="pg" :disabled="expPage + 1 >= pages(expTotal)" @click="expTo(expPage + 1)">
          下一页
        </button>
      </div>
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
  margin-bottom: var(--sv-s3);
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
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
}
.note {
  margin: 0 4px;
  line-height: 1.7;
}
.bar-card {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.vf {
  font-size: var(--sv-fs-footnote);
  margin: 0;
  line-height: 1.6;
}
.vf.ok {
  color: #1d7a3a;
}
.vf.bad {
  color: #b03030;
}
[data-theme='dark'] .vf.ok {
  color: #6ee787;
}
[data-theme='dark'] .vf.bad {
  color: #ff9d9d;
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
.btn:disabled {
  opacity: 0.5;
}
.row {
  padding: 12px 14px;
  border-bottom: 1px solid var(--sv-sep);
}
.row:last-child {
  border-bottom: none;
}
.row b {
  font-size: var(--sv-fs-footnote);
  display: block;
}
.row small {
  display: block;
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption2);
  margin-top: 3px;
}
.row em {
  font-style: normal;
  display: block;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
  margin-top: 3px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--sv-fs-footnote);
}
th {
  text-align: left;
  color: var(--sv-label3);
  font-weight: 500;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sv-sep);
}
td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--sv-sep);
  color: var(--sv-label2);
}
tr:last-child td {
  border-bottom: none;
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
