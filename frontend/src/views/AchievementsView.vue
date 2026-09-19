<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import http, { type ApiResp } from '@/api/http'
import { toast } from '@/stores/ui'

interface Badge { code: string; name: string; desc: string; unlocked: boolean; unlockedAt: string }

const items = ref<Badge[]>([])
const unlockedCount = ref(0)
const totalCount = ref(0)
const loading = ref(true)

onMounted(async () => {
  try {
    const { data } = await http.get<ApiResp<{ items: Badge[]; unlockedCount: number; totalCount: number }>>('/achievements')
    items.value = data.data.items
    unlockedCount.value = data.data.unlockedCount
    totalCount.value = data.data.totalCount
  } catch (e: any) {
    toast(e.message || '成就墙没打开')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="ach">
    <SvNavBar title="成就墙" :subtitle="`已点亮 ${unlockedCount} / ${totalCount}`" back="我的" back-to="/me" :large="false" />

    <div class="body">
      <p v-if="loading" class="sv-muted center">正在点亮…</p>
      <div v-else class="wall">
        <SvCard v-for="b in items" :key="b.code" :class="{ locked: !b.unlocked }" class="badge-card">
          <span class="medal" aria-hidden="true">{{ b.unlocked ? '🏅' : '🔒' }}</span>
          <b>{{ b.name }}</b>
          <small class="sv-muted">{{ b.desc }}</small>
          <em v-if="b.unlocked" class="at">{{ b.unlockedAt.slice(0, 10) }} 点亮</em>
          <em v-else class="at hint">还没发生，不着急</em>
        </SvCard>
      </div>
      <SvDisclaimer text="这里的每一枚都来自你照顾自己的时刻——没有排行榜，不和任何人比。" />
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.center { text-align: center; padding: var(--sv-s4) 0; }
.wall { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: var(--sv-s3); }
.badge-card { text-align: center; }
.badge-card.locked { opacity: .55; }
.medal { font-size: 30px; display: block; margin-bottom: 6px; }
.badge-card b { font-size: var(--sv-fs-subhead); display: block; }
.badge-card small { display: block; margin-top: 4px; line-height: 1.55; font-size: var(--sv-fs-caption1); }
.at { font-style: normal; display: block; margin-top: 8px; font-size: var(--sv-fs-caption2); color: var(--sv-mint); }
.at.hint { color: var(--sv-label3); }
</style>
