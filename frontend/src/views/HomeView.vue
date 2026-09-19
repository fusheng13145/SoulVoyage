<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http, { TOKEN_KEY, REFRESH_KEY, type ApiResp } from '../api/http'
import CrisisCard from '../components/CrisisCard.vue'

interface Me { userId: number; username: string; nickname: string; role: string }
const me = ref<Me | null>(null)
const crisisMode = ref(false)
const router = useRouter()

onMounted(async () => {
  const { data } = await http.get<ApiResp<Me>>('/auth/me')
  me.value = data.data
  try {
    const p = await http.get<ApiResp<{ crisisMode: boolean }>>('/emotions/profile')
    crisisMode.value = !!p.data.data.crisisMode
  } catch { /* 画像失败不影响首页 */ }
})

function logout() {
  http.post('/auth/logout', {}, {
    headers: { Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY)}` },
  }).finally(() => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    router.push('/login')
  })
}
</script>

<template>
  <div class="home" v-if="me">
    <header>
      <b>心屿漫行</b>
      <span class="hi">{{ me.nickname }}，今天感觉如何？</span>
      <button class="ghost" @click="logout">退出</button>
    </header>
    <main>
      <CrisisCard v-if="crisisMode" level="MEDIUM"
        headline="你已在关怀模式中：这段时间不必逼自己「想开点」，专业支持一直在这里" />
      <div class="tip">
        写日记让心屿陪你梳理情绪，进场景和「数字人」练一场不好开口的对话——所有记录加密存储，只有你能看到。
      </div>
      <div class="grid">
        <router-link to="/diary" class="tile">📔 情绪日记<small>感知 → 溯源 → 疏导 → 归档</small></router-link>
        <router-link to="/simulation" class="tile">🎭 人际模拟<small>4 大核心场景 · 导演模块多轮博弈</small></router-link>
        <router-link to="/plan" class="tile">🌿 自助练习<small>正念 · 54321 · 认知书写 · 跟练打卡</small></router-link>
        <router-link to="/insights" class="tile">📈 情绪洞察<small>情绪曲线 · 周画像</small></router-link>
        <router-link to="/archive" class="tile">🗂 成长档案<small>周报 · 限时一次性导出打印</small></router-link>
      </div>
      <p class="disclaimer">本内容为自助参考，不构成医学诊断。心理援助热线：12356</p>
    </main>
  </div>
</template>

<style scoped>
.home { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.hi { flex: 1; color: #5c6483; }
.ghost { border: 1px solid #dde3f3; background: #fff; border-radius: 8px; padding: 6px 14px; cursor: pointer; }
main { max-width: 860px; margin: 24px auto; padding: 0 16px; }
.tip { background: #eaf0ff; color: #40508c; border-radius: 10px; padding: 14px 16px; font-size: 14px; margin-bottom: 20px; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; }
.tile { background: #fff; border-radius: 12px; padding: 20px 16px; font-size: 16px; box-shadow: 0 2px 10px rgba(80,90,160,.08); display:flex; flex-direction:column; gap:6px;}
.tile small { color: #8a93b5; font-size: 12px; }
a.tile { text-decoration: none; color: inherit; cursor: pointer; }
.disclaimer { text-align: center; color: #9aa1bd; font-size: 12px; margin-top: 28px; }
</style>
