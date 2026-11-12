<script setup lang="ts">
import SvIcon from './SvIcon.vue'
import { vHaptic } from '@/directives/haptic'

const tabs = [
  { to: '/today', label: '今日', icon: 'i-today', key: 'Alt+1' },
  { to: '/diaries', label: '日记', icon: 'i-diary', key: 'Alt+2' },
  { to: '/practice', label: '练习', icon: 'i-heart', key: 'Alt+3' },
  { to: '/insights', label: '洞察', icon: 'i-chart', key: 'Alt+4' },
  { to: '/me', label: '我的', icon: 'i-me', key: 'Alt+5' },
]

function onKey(e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
  const links = Array.from(
    (e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('.tab'),
  )
  const idx = links.indexOf(document.activeElement as HTMLElement)
  if (idx < 0) return
  e.preventDefault()
  const next =
    e.key === 'Home'
      ? 0
      : e.key === 'End'
        ? links.length - 1
        : (idx + (e.key === 'ArrowRight' ? 1 : -1) + links.length) % links.length
  links[next]?.focus()
}
</script>

<template>
  <nav class="tabbar" aria-label="主导航" @keydown="onKey">
    <router-link
      v-for="t in tabs"
      :key="t.to"
      v-haptic
      :to="t.to"
      class="tab"
      :class="{ on: $route.path.startsWith(t.to) }"
      :aria-current="$route.path.startsWith(t.to) ? 'page' : undefined"
      :aria-keyshortcuts="t.key"
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
