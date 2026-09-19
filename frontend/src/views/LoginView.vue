<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import http, { TOKEN_KEY, REFRESH_KEY, type ApiResp } from '../api/http'

interface TokenResp { accessToken: string; refreshToken: string; userId: number; nickname: string; role: string }

const router = useRouter()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const path = mode.value === 'login' ? '/auth/login' : '/auth/register'
    const { data } = await http.post<ApiResp<TokenResp>>(path, {
      username: username.value, password: password.value, nickname: nickname.value,
    })
    localStorage.setItem(TOKEN_KEY, data.data.accessToken)
    localStorage.setItem(REFRESH_KEY, data.data.refreshToken)
    router.push('/')
  } catch (e: any) {
    error.value = e.response?.data?.msg ?? e.message ?? '操作失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <h1>心屿漫行 <small>SoulVoyage</small></h1>
      <p class="sub">多 Agent 青年心理自助成长平台</p>
      <div class="tabs">
        <button :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>
      <form @submit.prevent="submit">
        <input v-model="username" placeholder="用户名" maxlength="32" required />
        <input v-model="password" type="password" placeholder="密码（≥8位）" maxlength="64" required />
        <input v-if="mode === 'register'" v-model="nickname" placeholder="昵称（可选）" maxlength="32" />
        <p v-if="error" class="err">{{ error }}</p>
        <button class="primary" :disabled="loading">{{ loading ? '请稍候…' : (mode === 'login' ? '进入心屿' : '注册并进入') }}</button>
      </form>
      <p class="policy">本平台为心理自助工具，不构成医学诊断或治疗建议；如遇心理危机请拨打 12356。</p>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: grid; place-items: center; background: linear-gradient(160deg, #eef4ff, #f7effa); }
.card { width: 360px; background: #fff; border-radius: 16px; padding: 32px 28px; box-shadow: 0 12px 40px rgba(80, 90, 160, .15); }
h1 { margin: 0; font-size: 24px; } h1 small { font-size: 13px; color: #8a93b5; font-weight: normal; }
.sub { color: #7a819e; margin: 4px 0 20px; font-size: 13px; }
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.tabs button { flex: 1; padding: 8px; border: 1px solid #dde3f3; background: #f6f8fd; border-radius: 8px; cursor: pointer; }
.tabs button.on { background: #5b6cff; color: #fff; border-color: #5b6cff; }
form { display: flex; flex-direction: column; gap: 12px; }
input { padding: 10px 12px; border: 1px solid #dde3f3; border-radius: 8px; font-size: 14px; }
.primary { padding: 11px; background: #5b6cff; color: #fff; border: 0; border-radius: 8px; font-size: 15px; cursor: pointer; }
.err { color: #d4574e; font-size: 13px; margin: 0; }
.policy { color: #9aa1bd; font-size: 12px; margin-top: 20px; line-height: 1.6; }
</style>
