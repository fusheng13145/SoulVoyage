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
  { to: '/admin/board', label: '群体看板', icon: 'i-grid' },
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

    <div class="admin-main">
      <nav class="tabs" aria-label="管理端导航">
        <router-link
          v-for="n in NAV"
          :key="n.to"
          :to="n.to"
          class="tab"
          :class="{ on: $route.path === n.to }"
        >
          <SvIcon :name="n.icon" :size="15" tone="inherit" /><span>{{ n.label }}</span>
        </router-link>
      </nav>

      <div class="body">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  height: 100%;
}
.admin-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.tabs {
  display: flex;
  gap: var(--sv-s4);
  overflow-x: auto;
  padding: 0 var(--sv-s5);
  border-bottom: 1px solid var(--sv-sep);
  scrollbar-width: none;
}
.tabs::-webkit-scrollbar {
  display: none;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  padding: 10px 0;
  border-bottom: 1px solid transparent;
  margin-bottom: -1px;
  text-decoration: none;
  color: var(--sv-label2);
  background: transparent;
  font-size: var(--sv-fs-subhead);
  white-space: nowrap;
}
.tab.on {
  color: var(--sv-accent);
  border-bottom-color: var(--sv-accent);
}
.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sv-s5);
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
@media (min-width: 1024px) {
  .admin-main {
    flex-direction: row;
  }
  .tabs {
    width: 200px;
    flex: none;
    flex-direction: column;
    gap: 0;
    padding: var(--sv-s3) var(--sv-s2);
    border-right: 1px solid var(--sv-sep);
    border-bottom: none;
    overflow: visible;
  }
  .tab {
    padding: 11px 12px;
    border-bottom: none;
    border-left: 1px solid transparent;
    margin-bottom: 0;
    min-height: 40px;
  }
  .tab.on {
    border-left-color: var(--sv-accent);
    background: var(--sv-accent-soft);
  }
  .body {
    padding: var(--sv-s5) var(--sv-s6);
  }
}
</style>
