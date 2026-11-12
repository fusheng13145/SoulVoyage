<script setup lang="ts">
import { toastQueue } from '@/stores/ui'
</script>

<template>
  <Teleport to="#sv-layer" defer>
    <div class="sv-toast-host" role="status" aria-live="polite">
      <transition-group name="toast">
        <div v-for="t in toastQueue" :key="t.id" class="bubble">{{ t.msg }}</div>
      </transition-group>
    </div>
  </Teleport>
</template>

<style scoped>
.sv-toast-host {
  position: absolute;
  bottom: calc(var(--sv-safe-b) + 76px);
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  z-index: 120;
  pointer-events: none;
}
.bubble {
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  padding: 9px 14px;
  border-radius: var(--sv-r-ctl);
  font-size: var(--sv-fs-subhead);
  color: var(--sv-label);
  box-shadow: var(--sv-elev-1);
  max-width: 86%;
}
.toast-enter-active,
.toast-leave-active {
  transition:
    transform 0.18s ease,
    opacity 0.18s ease;
}
.toast-enter-from,
.toast-leave-to {
  transform: translateY(8px);
  opacity: 0;
}
</style>
