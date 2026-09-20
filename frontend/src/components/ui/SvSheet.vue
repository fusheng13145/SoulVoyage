<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { overlayDepth } from '@/stores/ui'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    full?: boolean
  }>(),
  { full: false, title: '' },
)
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const dragY = ref(0)
let startY: number | null = null

function onDragDown(e: PointerEvent) {
  startY = e.clientY
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
}
function onDragMove(e: PointerEvent) {
  if (startY === null) return
  dragY.value = Math.max(0, e.clientY - startY)
}
function onDragUp() {
  if (startY === null) return
  const shouldClose = dragY.value > 90
  startY = null
  dragY.value = 0
  if (shouldClose) close()
}
function close() {
  emit('update:modelValue', false)
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(
  () => props.modelValue,
  v => {
    overlayDepth.value += v ? 1 : -1
    if (v) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  },
)
onUnmounted(() => {
  if (props.modelValue) overlayDepth.value -= 1
})
</script>

<template>
  <Teleport to="#sv-layer">
    <transition name="sheet">
      <div v-if="modelValue" class="root" role="dialog" aria-modal="true" :aria-label="title">
        <div class="dim" @click="close" />
        <div
          class="panel"
          :class="{ full }"
          :style="dragY ? { transform: `translateY(${dragY}px)`, transition: 'none' } : {}"
        >
          <div
            class="grab"
            aria-hidden="true"
            @pointerdown="onDragDown"
            @pointermove="onDragMove"
            @pointerup="onDragUp"
            @pointercancel="onDragUp"
          />
          <h2 v-if="title">{{ title }}</h2>
          <div class="body"><slot /></div>
          <div v-if="$slots.footer" class="footer"><slot name="footer" /></div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.root {
  position: absolute;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
}
.dim {
  position: absolute;
  inset: 0;
  background: var(--sv-dim);
}
.panel {
  position: relative;
  width: 100%;
  background: var(--sv-card);
  border-radius: var(--sv-r-sheet) var(--sv-r-sheet) 0 0;
  padding: 10px var(--sv-s5) calc(28px + var(--sv-safe-b));
  max-height: 86%;
  display: flex;
  flex-direction: column;
  transform: translateY(0);
}
.panel.full {
  height: 86%;
}
.grab {
  width: 36px;
  height: 5px;
  border-radius: 99px;
  flex: none;
  background: var(--sv-label3);
  margin: 4px auto 14px;
  cursor: grab;
  touch-action: none;
  padding: 10px 0;
  background-clip: content-box;
}
.panel h2 {
  font-size: var(--sv-fs-title3);
  font-weight: 700;
  margin-bottom: var(--sv-s2);
}
.body {
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}
.body::-webkit-scrollbar {
  display: none;
}
.footer {
  margin-top: var(--sv-s4);
}
.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.3s;
}
.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}
.sheet-enter-active .panel,
.sheet-leave-active .panel {
  transition: transform 0.45s var(--sv-ease);
}
.sheet-enter-from .panel,
.sheet-leave-to .panel {
  transform: translateY(110%);
}
</style>
