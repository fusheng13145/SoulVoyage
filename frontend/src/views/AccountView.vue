<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import http, { type ApiResp } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { confirmDialog, toast } from '@/stores/ui'

const router = useRouter()
const auth = useAuthStore()

const me = computed(() => auth.me)
const msg = ref('')
const err = ref('')

const pw = ref({ old: '', next: '' })
const delPassword = ref('')
const exporting = ref(false)

onMounted(() => { auth.fetchMe(true).catch(() => { /* 401 由拦截器处理 */ }) })

async function cancelDeletion() {
  err.value = ''; msg.value = ''
  try {
    await http.post('/users/me/delete/cancel')
    msg.value = '已撤回注销申请，账号恢复正常。'
    sessionStorage.removeItem('sv_del_pending')
    await auth.fetchMe(true)
  } catch (e: any) { err.value = e.message || '撤回失败' }
}

async function changePassword() {
  err.value = ''; msg.value = ''
  if (pw.value.next.length < 8) { err.value = '新密码至少 8 位'; return }
  try {
    await http.put('/users/me/password', { oldPassword: pw.value.old, newPassword: pw.value.next })
    // 后端已吊销全部旧会话，本地一并下线重登
    auth.clear()
    router.replace('/login')
    toast('密码已修改，请用新密码重新登录')
  } catch (e: any) { err.value = e.message || '修改失败' }
}

/** 数据导出：GET 领限时链接 → POST 一次性领取明文 JSON → 浏览器落盘 */
async function exportData() {
  err.value = ''; msg.value = ''
  if (exporting.value) return
  const ok = await confirmDialog({
    title: '导出全部个人数据？',
    message: '包含全部日记、报告、画像、练习与风险记录的 JSON 数据包。链接限时 5 分钟、仅能领取一次，请注意保管。',
    confirmText: '生成数据包',
  })
  if (!ok) return
  exporting.value = true
  try {
    const { data } = await http.get<ApiResp<{ fileId: string }>>('/users/me/data-export')
    const r = await http.post<ApiResp<Record<string, unknown>>>(`/users/me/data-export/${data.data.fileId}`)
    const blob = new Blob([JSON.stringify(r.data.data, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `心屿漫行-我的数据-${new Date().toLocaleDateString('en-CA')}.json`
    a.click()
    URL.revokeObjectURL(a.href)
    msg.value = '数据包已开始下载（本链接已作废，导出请重新申请）。'
  } catch (e: any) {
    err.value = e.message || '导出失败'
  } finally {
    exporting.value = false
  }
}

async function requestDeletion() {
  err.value = ''
  if (!delPassword.value) { err.value = '请输入密码确认'; return }
  const ok = await confirmDialog({
    title: '申请注销账号？', danger: true, confirmText: '确认申请注销',
    message: '注销申请提交后进入 7 天冷静期，可登录撤回；到期后密钥销毁，所有数据不可恢复。',
  })
  if (!ok) return
  try {
    await http.post('/users/me/delete', { password: delPassword.value })
    auth.clear()
    router.replace('/login')
    toast('注销申请已提交，7 天内重新登录可撤回')
  } catch (e: any) { err.value = e.message || '申请失败' }
}
</script>

<template>
  <div class="account">
    <SvNavBar title="隐私中心" back="返回" :large="false" />

    <div class="body" v-if="me">
      <p v-if="err" class="err" role="alert">{{ err }}</p>
      <p v-if="msg" class="ok" role="status">{{ msg }}</p>

      <SvCard v-if="me.status === 3">
        <div class="notice">
          <SvIcon name="i-alert" :size="20" />
          <span>账号处于<b>注销冷静期</b>（申请于 {{ me.deletionRequestedAt || '—' }}），到期后数据将被不可恢复地销毁。</span>
          <button class="sv-btn sm ghost" @click="cancelDeletion">撤回注销</button>
        </div>
      </SvCard>

      <SvList title="我的数据">
        <SvCell :chevron="false" label="导出全部数据" icon="i-export"
          tone="var(--sv-indigo-soft)">
          <template #hint>包含日记、报告、画像、练习与风险记录的 JSON 包，限时一次性领取</template>
          <template #extra>
            <button class="sv-btn sm" :disabled="exporting" @click="exportData">
              {{ exporting ? '打包中…' : '导出' }}</button>
          </template>
        </SvCell>
      </SvList>

      <SvList title="修改密码">
        <SvCell :chevron="false" label="当前密码" icon="i-shield" tone="var(--sv-fill2)">
          <template #extra>
            <input v-model="pw.old" type="password" autocomplete="current-password" class="fin" maxlength="64" aria-label="当前密码" />
          </template>
        </SvCell>
        <SvCell :chevron="false" label="新密码" hint="8-64 位；修改后所有设备强制下线" icon="i-refresh" tone="var(--sv-fill2)">
          <template #extra>
            <input v-model="pw.next" type="password" autocomplete="new-password" class="fin" maxlength="64" aria-label="新密码" placeholder="至少 8 位" />
          </template>
        </SvCell>
        <div class="cell-pad">
          <button class="sv-btn" :disabled="!pw.old || !pw.next" @click="changePassword">修改并重新登录</button>
        </div>
      </SvList>

      <SvList>
        <template #title><span class="danger-title">注销账号</span></template>
        <SvCell :chevron="false" label="输入密码确认注销" hint="7 天冷静期内重新登录可撤回；到期后密钥销毁，所有加密内容永久无法解密"
          icon="i-trash" tone="color-mix(in srgb, var(--sv-red) 14%, transparent)">
          <template #extra>
            <input v-model="delPassword" type="password" autocomplete="off" class="fin danger-input" maxlength="64" aria-label="输入密码确认注销" />
          </template>
        </SvCell>
        <div class="cell-pad">
          <button class="sv-btn danger" :disabled="!delPassword" @click="requestDeletion">申请注销</button>
        </div>
      </SvList>

      <p class="sv-cap note">心屿漫行对全部内容做信封加密，服务端无法读取明文；注销即遗忘。</p>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.err { color: var(--sv-red); font-size: var(--sv-fs-footnote); margin-bottom: var(--sv-s2); }
.ok { color: var(--sv-mint); font-size: var(--sv-fs-footnote); background: color-mix(in srgb, var(--sv-mint) 10%, var(--sv-card));
  border-radius: var(--sv-r-ctl); padding: 10px 14px; margin-bottom: var(--sv-s2); }
.notice { display: flex; align-items: center; gap: var(--sv-s3); flex-wrap: wrap; font-size: var(--sv-fs-footnote); }
.notice b { font-weight: 700; }
.notice .sv-btn { margin-left: auto; }
.danger-title { color: var(--sv-red); }
.cell-pad { padding: 12px 16px; }
.fin { width: 140px; border: 1px solid var(--sv-sep); background: var(--sv-bg); border-radius: 10px;
  padding: 9px 12px; font-size: var(--sv-fs-footnote); color: var(--sv-label); outline: none; font-family: inherit; }
.fin:focus { border-color: var(--sv-indigo); }
.danger-input:focus { border-color: var(--sv-red); }
.note { text-align: center; margin-top: var(--sv-s4); line-height: 1.6; }
</style>
