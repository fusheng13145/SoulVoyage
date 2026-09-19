<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http, { REFRESH_KEY, TOKEN_KEY, type ApiResp } from '../api/http'

interface Me {
  userId: number; username: string; nickname: string; role: string
  status: number; deletionRequestedAt: string
}

const router = useRouter()
const me = ref<Me | null>(null)
const msg = ref('')
const err = ref('')

const pw = ref({ old: '', next: '' })
const delPassword = ref('')
const exporting = ref(false)

async function loadMe() {
  const { data } = await http.get<ApiResp<Me>>('/auth/me')
  me.value = data.data
}
onMounted(() => { loadMe().catch(() => { /* 401 由拦截器处理 */ }) })

function hardLogout() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem('sv_del_pending')
  router.push('/login')
}

async function cancelDeletion() {
  err.value = ''; msg.value = ''
  try {
    await http.post('/users/me/delete/cancel')
    msg.value = '已撤回注销申请，账号恢复正常。'
    sessionStorage.removeItem('sv_del_pending')
    await loadMe()
  } catch (e: any) { err.value = e.message || '撤回失败' }
}

async function changePassword() {
  err.value = ''; msg.value = ''
  if (pw.value.next.length < 8) { err.value = '新密码至少 8 位'; return }
  try {
    await http.put('/users/me/password', { oldPassword: pw.value.old, newPassword: pw.value.next })
    // 后端已吊销全部旧会话，本地一并下线重登
    hardLogout()
  } catch (e: any) { err.value = e.message || '修改失败' }
}

/** 数据导出：GET 领限时链接 → POST 一次性领取明文 JSON → 浏览器落盘 */
async function exportData() {
  err.value = ''; msg.value = ''
  if (exporting.value) return
  exporting.value = true
  try {
    const { data } = await http.get<ApiResp<{ fileId: string }>>('/users/me/data-export')
    const r = await http.post<ApiResp<Record<string, unknown>>>(`/users/me/data-export/${data.data.fileId}`)
    const blob = new Blob([JSON.stringify(r.data.data, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `心屿漫行-我的数据-${new Date().toISOString().slice(0, 10)}.json`
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
  if (!window.confirm('注销申请提交后进入 7 天冷静期，可登录撤回；到期后所有数据将被不可恢复地销毁。确定申请？')) return
  try {
    await http.post('/users/me/delete', { password: delPassword.value })
    hardLogout()
  } catch (e: any) { err.value = e.message || '申请失败' }
}
</script>

<template>
  <div class="account">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>🔐 隐私中心</b>
    </header>
    <main v-if="me">
      <p v-if="err" class="err">{{ err }}</p>
      <p v-if="msg" class="ok">{{ msg }}</p>

      <div v-if="me.status === 3" class="notice">
        账号处于<b>注销冷静期</b>（申请于 {{ me.deletionRequestedAt || '—' }}），到期后数据将被不可恢复地销毁。
        <button class="ghost" @click="cancelDeletion">撤回注销</button>
      </div>

      <section>
        <h3>我的数据</h3>
        <p class="sub">生成一份包含全部日记、报告、画像、练习与风险记录的 JSON 数据包。链接限时 5 分钟、仅能领取一次。</p>
        <button class="primary" :disabled="exporting" @click="exportData">
          {{ exporting ? '打包中…' : '导出我的全部数据' }}</button>
      </section>

      <section>
        <h3>修改密码</h3>
        <p class="sub">修改成功后所有设备将被强制下线，需用新密码重新登录。</p>
        <input v-model="pw.old" type="password" placeholder="当前密码" maxlength="64" />
        <input v-model="pw.next" type="password" placeholder="新密码（8-64 位）" maxlength="64" />
        <button class="primary" :disabled="!pw.old || !pw.next" @click="changePassword">修改并重新登录</button>
      </section>

      <section class="danger-zone">
        <h3>注销账号</h3>
        <p class="sub">7 天冷静期内重新登录可撤回；到期后密钥销毁，所有加密内容将永久无法解密。</p>
        <input v-model="delPassword" type="password" placeholder="输入密码确认注销" maxlength="64" />
        <button class="primary danger" :disabled="!delPassword" @click="requestDeletion">申请注销</button>
      </section>

      <p class="disclaimer">心屿漫行对全部内容做 ✦ 信封加密，服务端无法读取明文；注销即遗忘。</p>
    </main>
  </div>
</template>

<style scoped>
.account { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
main { max-width: 640px; margin: 24px auto; padding: 0 16px; }
.err { color: #d4574e; font-size: 14px; }
.ok { color: #2f6a4a; font-size: 14px; background: #e8f6ee; border-radius: 10px; padding: 10px 14px; }
.notice { background: #fdf1e0; color: #8a5a1d; border-radius: 12px; padding: 14px 16px; font-size: 14px; margin-bottom: 16px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
section { background: #fff; border-radius: 12px; padding: 18px 20px; margin-bottom: 16px; box-shadow: 0 2px 10px rgba(80,90,160,.08); display: flex; flex-direction: column; gap: 10px; }
section h3 { margin: 0; font-size: 16px; color: #40466b; }
.sub { margin: 0; font-size: 13px; color: #7c84a6; line-height: 1.6; }
input { border: 1px solid #dde3f3; border-radius: 8px; padding: 10px 12px; font-size: 14px; }
.primary { background: #5b6cff; color: #fff; border: 0; border-radius: 8px; padding: 10px; font-size: 14px; cursor: pointer; align-self: flex-start; }
.primary:disabled { opacity: .55; cursor: not-allowed; }
.primary.danger { background: #d4574e; }
.danger-zone { border: 1px solid #f2d4d1; }
.ghost { border: 1px solid #a8722f; color: #8a5a1d; background: #fff; border-radius: 8px; padding: 5px 14px; font-size: 13px; cursor: pointer; }
.disclaimer { text-align: center; color: #9aa1bd; font-size: 12px; margin-top: 18px; }
</style>
