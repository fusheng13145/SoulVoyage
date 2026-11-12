<script setup lang="ts">
import SvIcon from './SvIcon.vue'
import { vHaptic } from '@/directives/haptic'

const tabs = [
  { to: '/today', label: '今日', icon: 'i-today' },
  { to: '/diaries', label: '日记', icon: 'i-diary' },
  { to: '/practice', label: '练习', icon: 'i-heart' },
  { to: '/insights', label: '洞察', icon: 'i-chart' },
  { to: '/me', label: '我的', icon: 'i-me' },
]
</script>

<template>
  <nav class="tabbar" aria-label="主导航">
    <router-link
      v-for="t in tabs"
      :key="t.to"
      v-haptic
      :to="t.to"
      class="tab"
      :class="{ on: $route.path.startsWith(t.to) }"
      :aria-current="$route.path.startsWith(t.to) ? 'page' : undefined"
    >
      <SvIcon :name="t.icon" :size="22" tone="inherit" />
      <span>{{ t.label }}</span>
    </router-link>
  </nav>
</template>

<style scoped>
.tabbar {
  display: flex;
  background: var(--sv-bg);
  border-top: 1px solid var(--sv-sep);
  min-height: 60px;
  padding: 4px 8px calc(4px + var(--sv-safe-b));
}
.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 4px 0;
  min-height: 48px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label3);
  text-decoration: none;
  transition: color 0.18s;
}
.tab.on {
  color: var(--sv-accent);
}
</style>
