<script setup lang="ts">
import { computed } from 'vue'
import { dialogState, resolveDialog } from '@/stores/ui'
import SvIcon from './SvIcon.vue'

const open = computed(() => !!dialogState.value)
function onKey(e: KeyboardEvent) {
  if (!open.value) return
  if (e.key === 'Escape') resolveDialog(false)
}
</script>

<template>
  <Teleport to="#sv-layer" defer>
    <transition name="dlg">
      <div v-if="dialogState" class="wrap" role="presentation" @keydown="onKey">
        <div class="veil" @click="resolveDialog(false)" />
        <div class="box" role="alertdialog" aria-modal="true" :aria-label="dialogState.title">
          <b>{{ dialogState.title }}</b>
          <p v-if="dialogState.message">{{ dialogState.message }}</p>
          <div class="btns">
            <button class="c" @click="resolveDialog(false)">{{ dialogState.cancelText || '取消' }}</button>
            <button
              class="ok"
              :class="{ danger: dialogState.danger }"
              data-autofocus
              @click="resolveDialog(true)"
            >
              {{ dialogState.confirmText || '确定' }}
              <SvIcon name="i-check" :size="16" />
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.wrap {
  position: absolute;
  inset: 0;
  z-index: 130;
  display: grid;
  place-items: center;
}
.veil {
  position: absolute;
  inset: 0;
  background: var(--sv-dim);
}
.box {
  position: relative;
  width: min(280px, 84vw);
  background: color-mix(in srgb, var(--sv-card) 92%, transparent);
  backdrop-filter: var(--sv-blur);
  -webkit-backdrop-filter: var(--sv-blur);
  border-radius: var(--sv-r-ctl);
  text-align: center;
  overflow: hidden;
  box-shadow: var(--sv-sh-2);
}
[data-theme='dark'] .box {
  border: 1px solid var(--sv-sep);
}
.box b {
  display: block;
  padding: 19px 16px 5px;
  font-size: var(--sv-fs-headline);
}
.box p {
  padding: 0 16px 19px;
  font-size: var(--sv-fs-callout);
  color: var(--sv-label2);
  line-height: 1.4;
}
.btns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-top: 1px solid var(--sv-sep);
}
.btns button {
  min-height: 44px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: var(--sv-fs-headline);
  color: var(--sv-blue);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.btns button + button {
  border-left: 1px solid var(--sv-sep);
}
.btns .ok.danger {
  color: var(--sv-red);
}
.dlg-enter-active,
.dlg-leave-active {
  transition: opacity 0.25s;
}
.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}
</style>
