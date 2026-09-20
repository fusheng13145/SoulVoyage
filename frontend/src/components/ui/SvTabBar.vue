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
      <SvIcon :name="t.icon" :size="26" tone="inherit" />
      <span>{{ t.label }}</span>
    </router-link>
  </nav>
</template>

<style scoped>
.tabbar {
  display: flex;
  background: color-mix(in srgb, var(--sv-bg) 78%, transparent);
  backdrop-filter: var(--sv-blur);
  -webkit-backdrop-filter: var(--sv-blur);
  border-top: 1px solid var(--sv-sep);
  padding: 6px 8px calc(6px + var(--sv-safe-b));
}
.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 5px 0;
  min-height: 44px;
  font-size: 10.5px;
  color: var(--sv-label2);
  text-decoration: none;
  transition:
    color 0.2s,
    transform 0.2s var(--sv-ease);
}
.tab.on {
  color: var(--sv-indigo);
}
.tab:active {
  transform: scale(0.92);
}
</style>
