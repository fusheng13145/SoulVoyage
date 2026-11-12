<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useCrisisStore } from '@/stores/crisis'
import SvIcon from './ui/SvIcon.vue'

/** risk:crisis 生效时全局顶部常驻求助横幅（DS3 危机规范） */
const crisis = useCrisisStore()
const route = useRoute()
const show = computed(() => crisis.crisisMode && route.path !== '/crisis')

onMounted(() => {
  crisis.refreshProfile().catch(() => {
    /* 画像失败不阻塞外壳 */
  })
})
</script>

<template>
  <router-link v-if="show" to="/crisis" class="banner" role="status">
    <SvIcon name="i-heart" :size="16" tone="inherit" />
    <span>你正处于被关怀模式 · 随时可以求助</span>
    <b>查看支持 <SvIcon name="i-arrow" :size="13" tone="inherit" /></b>
  </router-link>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--sv-danger);
  color: var(--sv-on-accent);
  padding: 10px 16px;
  font-size: var(--sv-fs-footnote);
  flex: none;
}
.banner b {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-weight: 500;
}
</style>
