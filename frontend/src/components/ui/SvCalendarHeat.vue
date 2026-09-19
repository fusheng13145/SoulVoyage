<script setup lang="ts">
import { valenceColor } from '@/utils/emotions'
import { vHaptic } from '@/directives/haptic'

export interface HeatDay {
  date: string        // YYYY-MM-DD
  valence?: number    // 当日平均效价；无记录为 undefined
  count?: number
  label?: string      // 当日主情绪（tooltip/读屏）
}

withDefaults(defineProps<{
  days: HeatDay[]
  mode?: 'strip' | 'month'
}>(), { mode: 'strip' })

const emit = defineEmits<{ pick: [HeatDay] }>()

const titleOf = (d: HeatDay) =>
  `${d.date.slice(5).replace('-', '/')}：${d.valence === undefined ? '没有记录' : `效价 ${d.valence} · ${d.label || '情绪记录'} · ${d.count || 1} 个数据点`}`
</script>

<template>
  <!-- strip：今日页近 14 天热力条；month：健康 App 式月历（M7 打卡后启用） -->
  <div v-if="mode === 'strip'" class="heat" role="list" aria-label="近两周情绪热力条">
    <button
      v-for="d in days" :key="d.date" class="cell" v-haptic
      role="listitem" :aria-label="titleOf(d)" :title="titleOf(d)"
      :style="{ background: d.valence === undefined ? 'var(--sv-fill2)' : valenceColor(d.valence) }"
      @click="emit('pick', d)"
    >
      <span v-if="d.date === days[days.length - 1]?.date">今</span>
    </button>
  </div>
  <div v-else class="month" aria-label="月历情绪热力">
    <span
      v-for="d in days" :key="d.date" class="mcell"
      :title="titleOf(d)" :aria-label="titleOf(d)"
      :style="{ background: d.valence === undefined ? 'var(--sv-fill3)' : valenceColor(d.valence) }"
    >{{ Number(d.date.slice(-2)) }}</span>
  </div>
</template>

<style scoped>
.heat { display: flex; gap: 5px; margin-top: 10px; }
.cell {
  flex: 1; height: 34px; border-radius: 9px; border: none; cursor: pointer;
  transition: transform 0.2s var(--sv-ease); padding: 0; position: relative;
}
.cell:active { transform: scale(1.15); }
.cell span {
  position: absolute; bottom: 3px; left: 50%; transform: translateX(-50%);
  font-size: 8px; color: var(--sv-label2);
}
.month { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.mcell {
  aspect-ratio: 1; border-radius: 8px; display: grid; place-items: center;
  font-size: var(--sv-fs-caption2); color: var(--sv-label2);
}
</style>
