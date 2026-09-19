<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import SvIcon from '@/components/ui/SvIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { toast } from '@/stores/ui'

const router = useRouter()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    if (mode.value === 'login') await auth.login(username.value, password.value)
    else await auth.register(username.value, password.value, nickname.value)
    toast(mode.value === 'login' ? '欢迎回到心屿' : '登岛成功，从这里开始漫行')
    router.replace('/today')
  } catch (e: any) {
    error.value = e.message ?? '操作失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="brand">
      <span class="logo"><SvIcon name="i-island" :size="56" tone="inherit" /></span>
      <h1>心屿漫行</h1>
      <p class="sv-muted">多 Agent 青年心理自助成长平台</p>
    </div>

    <form class="card sv-surface" @submit.prevent="submit">
      <div class="seg" role="tablist" aria-label="登录或注册">
        <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>

      <label>用户名
        <input v-model="username" autocomplete="username" maxlength="32" required />
      </label>
      <label>密码
        <input v-model="password" type="password" autocomplete="current-password" placeholder="≥ 8 位" maxlength="64" required />
      </label>
      <label v-if="mode === 'register'">昵称（可选）
        <input v-model="nickname" maxlength="32" />
      </label>

      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <button class="sv-btn" type="submit" :disabled="loading || !username || !password">
        {{ loading ? '请稍候…' : (mode === 'login' ? '进入心屿' : '注册并进入') }}</button>
    </form>

    <router-link to="/crisis" class="help">需要支持？查看危机资源</router-link>
    <p class="policy sv-cap">本平台为心理自助工具，不构成医学诊断或治疗建议；如遇心理危机请拨打 12356。</p>
  </div>
</template>

<style scoped>
.login { display: flex; flex-direction: column; align-items: center; padding: calc(var(--sv-safe-t) + 64px) var(--sv-s5) var(--sv-s6); }
.brand { text-align: center; margin-bottom: var(--sv-s6); }
.logo { display: grid; place-items: center; width: 88px; height: 88px; margin: 0 auto var(--sv-s3);
  border-radius: 28px; background: linear-gradient(160deg, var(--sv-indigo), var(--sv-blue)); color: #fff;
  box-shadow: var(--sv-sh-2); }
h1 { font-size: var(--sv-fs-title1); letter-spacing: 2px; }
.brand .sv-muted { margin-top: 4px; }
.card { width: 100%; max-width: 380px; padding: var(--sv-s5); display: flex; flex-direction: column; gap: var(--sv-s4); }
.seg { display: flex; padding: 2px; gap: 2px; background: var(--sv-fill2); border-radius: 10px; }
.seg button { flex: 1; min-height: 32px; border: none; border-radius: 8px; background: transparent; cursor: pointer;
  font-size: var(--sv-fs-subhead); color: var(--sv-label); font-family: inherit; }
.seg button.on { background: var(--sv-card); font-weight: 600; box-shadow: 0 1px 4px rgba(0,0,0,.12); }
label { display: flex; flex-direction: column; gap: 6px; font-size: var(--sv-fs-footnote); color: var(--sv-label2); }
input { border: 1px solid var(--sv-sep); background: var(--sv-bg); border-radius: var(--sv-r-ctl);
  padding: 12px 14px; font-size: var(--sv-fs-subhead); color: var(--sv-label); outline: none; font-family: inherit; }
input:focus { border-color: var(--sv-indigo); }
.err { color: var(--sv-red); font-size: var(--sv-fs-footnote); }
.help { margin-top: var(--sv-s5); font-size: var(--sv-fs-footnote); }
.policy { text-align: center; margin-top: var(--sv-s2); line-height: 1.6; max-width: 320px; }
</style>
