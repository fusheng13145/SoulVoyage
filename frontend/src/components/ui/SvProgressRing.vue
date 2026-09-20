<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    progress: number // 0..1
    size?: number
    stroke?: number
    color?: string
  }>(),
  { size: 64, stroke: 6, color: 'var(--sv-indigo)' },
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
        stroke="var(--sv-fill2)"
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
        style="transition: stroke-dashoffset 0.5s cubic-bezier(0.32, 0.72, 0, 1)"
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
  font-size: var(--sv-fs-footnote);
  font-weight: 600;
  color: var(--sv-label2);
}
</style>
