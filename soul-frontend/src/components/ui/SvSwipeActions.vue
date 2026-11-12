<script setup lang="ts">
import { ref } from 'vue'
import SvIcon from './SvIcon.vue'

const props = withDefaults(
  defineProps<{
    action?: 'delete' | 'star'
    threshold?: number
  }>(),
  { action: 'delete', threshold: 68 },
)

const emit = defineEmits<{ confirm: [] }>()
const dx = ref(0)
const open = ref(false)
let startX: number | null = null

function down(e: PointerEvent) {
  if ((e.target as HTMLElement).closest('button,a,input,textarea,[role=button]')) return
  startX = e.clientX
}
function move(e: PointerEvent) {
  if (startX === null) return
  const d = e.clientX - startX
  if (d < 0) dx.value = Math.max(-props.threshold - 12, d)
}
function up() {
  if (startX === null) return
  startX = null
  open.value = dx.value < -props.threshold
  dx.value = open.value ? -props.threshold : 0
}
function confirmAct() {
  emit('confirm')
  reset()
}

function reset() {
  open.value = false
  dx.value = 0
}
defineExpose({ reset })
</script>

<template>
  <div
    class="swipe"
    @pointerdown="down"
    @pointermove="move"
    @pointerup="up"
    @pointercancel="up"
    @click.capture="open && reset()"
  >
    <button
      v-show="open"
      class="act"
      :class="action"
      :aria-label="action === 'delete' ? '删除' : '收藏'"
      @click.stop="confirmAct"
    >
      <SvIcon :name="action === 'delete' ? 'i-trash' : 'i-medal'" :size="18" tone="inherit" />
    </button>
    <div
      class="row"
      :style="{
        transform: `translateX(${dx}px)`,
        transition: startX === null ? 'transform .3s cubic-bezier(0.32,0.72,0,1)' : 'none',
      }"
    >
      <slot />
    </div>
  </div>
</template>

<style scoped>
.swipe {
  position: relative;
  overflow: hidden;
  touch-action: pan-y;
}
.act {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 64px;
  border: none;
  cursor: pointer;
  display: grid;
  place-items: center;
  color: #fff;
  background: var(--sv-red);
}
.act.star {
  background: var(--sv-amber);
}
.row {
  position: relative;
  background: var(--sv-card);
}
</style>
