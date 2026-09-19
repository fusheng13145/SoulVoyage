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
  position: absolute; top: calc(var(--sv-safe-t) + 60px); left: 0; right: 0;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  z-index: 120; pointer-events: none;
}
.bubble {
  background: color-mix(in srgb, var(--sv-card) 90%, transparent);
  backdrop-filter: var(--sv-blur); -webkit-backdrop-filter: var(--sv-blur);
  border: 1px solid var(--sv-sep);
  padding: 10px 18px; border-radius: var(--sv-r-pill);
  font-size: var(--sv-fs-footnote); color: var(--sv-label);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.2); max-width: 86%;
}
.toast-enter-active, .toast-leave-active { transition: transform 0.4s var(--sv-ease), opacity 0.3s; }
.toast-enter-from, .toast-leave-to { transform: translateY(-80px); opacity: 0; }
</style>
