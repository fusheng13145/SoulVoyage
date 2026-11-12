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
    toast(mode.value === 'login' ? '已登录' : '已注册')
    router.replace('/today')
  } catch (e) {
    error.value = (e as Error).message ?? '操作失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="brand">
      <span class="logo" aria-hidden="true"><SvIcon name="i-island" :size="28" tone="inherit" /></span>
      <h1>心屿漫行</h1>
      <p class="sv-muted">多 Agent 青年心理自助成长平台</p>
    </div>

    <form class="card sv-surface" @submit.prevent="submit">
      <div class="seg" role="tablist" aria-label="登录或注册">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'login'"
          :class="{ on: mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'register'"
          :class="{ on: mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <label
        >用户名
        <input v-model="username" class="sv-input" autocomplete="username" maxlength="32" required />
      </label>
      <label
        >密码
        <input
          v-model="password"
          class="sv-input"
          type="password"
          autocomplete="current-password"
          placeholder="≥ 8 位"
          maxlength="64"
          required
        />
      </label>
      <label v-if="mode === 'register'"
        >昵称（可选）
        <input v-model="nickname" class="sv-input" maxlength="32" />
      </label>

      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <button class="sv-btn" type="submit" :disabled="loading || !username || !password">
        {{ loading ? '请稍候…' : mode === 'login' ? '登录' : '注册并进入' }}
      </button>
    </form>

    <router-link to="/crisis" class="help">需要支持？查看危机资源</router-link>
    <p class="policy sv-cap">本平台为心理自助工具，不构成医学诊断或治疗建议；如遇心理危机请拨打 12356。</p>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: calc(var(--sv-safe-t) + 56px) var(--sv-s5) var(--sv-s6);
}
.brand {
  text-align: center;
  margin-bottom: var(--sv-s6);
}
.logo {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  margin: 0 auto var(--sv-s3);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-card);
  color: var(--sv-accent);
  background: var(--sv-card);
}
h1 {
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-title1);
  letter-spacing: 2px;
  font-weight: 600;
}
.brand .sv-muted {
  margin-top: 6px;
}
.card {
  width: 100%;
  max-width: 380px;
  padding: var(--sv-s6) var(--sv-s5);
  display: flex;
  flex-direction: column;
  gap: var(--sv-s4);
}
.seg {
  display: flex;
  gap: var(--sv-s4);
  border-bottom: 1px solid var(--sv-sep);
  margin-bottom: 4px;
}
.seg button {
  flex: 0 0 auto;
  min-height: 36px;
  border: none;
  border-bottom: 1px solid transparent;
  border-radius: 0;
  background: transparent;
  cursor: pointer;
  font-size: var(--sv-fs-body);
  color: var(--sv-label2);
  font-family: inherit;
  padding: 0 2px;
  margin-bottom: -1px;
}
.seg button.on {
  color: var(--sv-accent);
  border-bottom-color: var(--sv-accent);
  font-weight: 500;
}
label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
}
.err {
  color: var(--sv-danger);
  font-size: var(--sv-fs-footnote);
}
.help {
  margin-top: var(--sv-s5);
  font-size: var(--sv-fs-footnote);
}
.policy {
  text-align: center;
  margin-top: var(--sv-s2);
  line-height: 1.7;
  max-width: 320px;
}
</style>
