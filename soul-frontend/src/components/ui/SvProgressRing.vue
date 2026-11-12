<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    progress: number // 0..1
    size?: number
    stroke?: number
    color?: string
  }>(),
  { size: 64, stroke: 4, color: 'var(--sv-accent)' },
)

const r = computed(() => (props.size - props.stroke) / 2)
const c = computed(() => 2 * Math.PI * r.value)
</script>

<template>
  <div class="ring" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :style="{ transform: 'rotate(-90deg)' }" aria-hidden="true">
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        :stroke-width="stroke"
        stroke="var(--sv-sep)"
      />
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        :stroke-width="stroke"
        :stroke="color"
        stroke-linecap="round"
        :stroke-dasharray="c"
        :stroke-dashoffset="c * (1 - Math.min(1, Math.max(0, progress)))"
        style="transition: stroke-dashoffset 0.18s ease"
      />
    </svg>
    <span class="center"><slot /></span>
  </div>
</template>

<style scoped>
.ring {
  position: relative;
  flex: none;
}
svg {
  display: block;
}
.center {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-family: var(--sv-num);
  font-size: var(--sv-fs-footnote);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--sv-label2);
}
</style>
