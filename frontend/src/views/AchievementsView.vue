<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import { achievementApi } from '@/api/domains/achievement'
import { toast } from '@/stores/ui'

interface Badge {
  code: string
  name: string
  desc: string
  unlocked: boolean
  unlockedAt: string
}

const items = ref<Badge[]>([])
const unlockedCount = ref(0)
const totalCount = ref(0)
const loading = ref(true)

onMounted(async () => {
  try {
    const data = await achievementApi.wall<{
      items: Badge[]
      unlockedCount: number
      totalCount: number
    }>()
    items.value = data.items
    unlockedCount.value = data.unlockedCount
    totalCount.value = data.totalCount
  } catch (e) {
    toast((e as Error).message || '成就墙没打开')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="ach">
    <SvNavBar
      title="成就墙"
      :subtitle="`已点亮 ${unlockedCount} / ${totalCount}`"
      back="我的"
      back-to="/me"
      :large="false"
    />

    <SvPageBody>
      <p v-if="loading" class="sv-muted sv-center">正在点亮…</p>
      <div v-else class="wall">
        <SvCard v-for="b in items" :key="b.code" :class="{ locked: !b.unlocked }" class="badge-card">
          <span class="mark" aria-hidden="true" />
          <b>{{ b.name }}</b>
          <small class="sv-muted">{{ b.desc }}</small>
          <em v-if="b.unlocked" class="at">{{ b.unlockedAt.slice(0, 10) }} 点亮</em>
          <em v-else class="at hint">尚未点亮</em>
        </SvCard>
      </div>
      <SvDisclaimer text="这里的每一枚都来自你照顾自己的时刻——没有排行榜，不和任何人比。" />
    </SvPageBody>
  </div>
</template>

<style scoped>
.wall {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: var(--sv-s3);
}
.badge-card {
  text-align: left;
  border-left: 1px solid transparent;
}
.badge-card.locked {
  opacity: 0.55;
}
.mark {
  display: block;
  width: 10px;
  height: 10px;
  margin-bottom: 8px;
  border: 1px solid var(--sv-label3);
  background: transparent;
}
.badge-card:not(.locked) {
  border-left-color: var(--sv-accent);
}
.badge-card:not(.locked) .mark {
  border-color: var(--sv-accent);
  background: var(--sv-accent);
}
.badge-card b {
  font-size: var(--sv-fs-body);
  font-weight: 500;
  display: block;
}
.badge-card small {
  display: block;
  margin-top: 4px;
  line-height: var(--sv-lh-body);
  font-size: var(--sv-fs-footnote);
}
.at {
  font-style: normal;
  display: block;
  margin-top: 8px;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-accent);
}
.at.hint {
  color: var(--sv-label3);
}
</style>
