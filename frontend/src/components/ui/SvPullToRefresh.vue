<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ refresh: [] }>()

const pulling = ref(0)
const refreshing = ref(false)
const startY = ref<number | null>(null)
const root = ref<HTMLElement | null>(null)

function down(e: PointerEvent) {
  if (refreshing.value) return
  const sc = root.value?.closest('.sv-scroll') as HTMLElement | null
  if (sc && sc.scrollTop > 0) return
  startY.value = e.clientY
}
function move(e: PointerEvent) {
  if (startY.value === null || refreshing.value) return
  pulling.value = Math.min(72, Math.max(0, (e.clientY - startY.value) * 0.4))
}
function up() {
  if (startY.value === null) return
  startY.value = null
  if (pulling.value > 48) {
    refreshing.value = true
    pulling.value = 48
    emit('refresh')
  } else {
    pulling.value = 0
  }
}
function done() {
  refreshing.value = false
  pulling.value = 0
}
defineExpose({ done })
</script>

<template>
  <div ref="root" class="ptr" @pointerdown="down" @pointermove="move" @pointerup="up" @pointercancel="up">
    <div class="indicator" :style="{ height: pulling + 'px' }" aria-hidden="true">
      <span class="spin" :class="{ go: refreshing }" />
    </div>
    <slot />
  </div>
</template>

<style scoped>
.ptr { touch-action: pan-y; }
.indicator { overflow: hidden; display: grid; place-items: center; transition: height 0.3s var(--sv-ease); }
.spin {
  width: 20px; height: 20px; border-radius: 50%;
  border: 2.5px solid var(--sv-label3); border-top-color: var(--sv-indigo);
  opacity: 0.9;
}
.spin.go { animation: rot 0.8s linear infinite; }
@keyframes rot { to { transform: rotate(360deg); } }
</style>
