<script setup lang="ts">
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import { useAuthStore } from '@/stores/auth'

defineProps<{ title: string; subtitle?: string }>()

const router = useRouter()
const auth = useAuthStore()

const NAV = [
  { to: '/admin', label: '总览', icon: 'i-grid' },
  { to: '/admin/tasks', label: '任务监控', icon: 'i-clock' },
  { to: '/admin/risk', label: '风险复核', icon: 'i-shield' },
  { to: '/admin/content', label: '内容管理', icon: 'i-doc' },
  { to: '/admin/users', label: '用户支持', icon: 'i-me' },
  { to: '/admin/ops', label: '审计密钥', icon: 'i-folder' },
]

async function quit() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="admin-page">
    <SvNavBar :title="title" :subtitle="subtitle" back="用户端" back-to="/today" :large="false">
      <template #actions>
        <button class="quit" @click="quit">
          <SvIcon name="i-logout" :size="16" tone="inherit" /><span>退出</span>
        </button>
      </template>
    </SvNavBar>

    <nav class="tabs" aria-label="管理端导航">
      <router-link v-for="n in NAV" :key="n.to" :to="n.to" class="tab" :class="{ on: $route.path === n.to }">
        <SvIcon :name="n.icon" :size="15" tone="inherit" /><span>{{ n.label }}</span>
      </router-link>
    </nav>

    <div class="body">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: var(--sv-s3) var(--sv-s4) 0;
  scrollbar-width: none;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  padding: 8px 13px;
  border-radius: 999px;
  text-decoration: none;
  color: var(--sv-label2);
  background: var(--sv-card);
  font-size: var(--sv-fs-footnote);
  border: 1px solid var(--sv-sep);
  white-space: nowrap;
}
.tab.on {
  color: #fff;
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
}
.body {
  padding: var(--sv-s4);
}
.quit {
  border: none;
  background: transparent;
  color: var(--sv-label3);
  cursor: pointer;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 10px 8px;
}
</style>
