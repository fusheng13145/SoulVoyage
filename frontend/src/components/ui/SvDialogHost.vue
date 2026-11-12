<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { dialogState, overlayDepth, resolveDialog } from '@/stores/ui'
import { useFocusTrap } from '@/composables/focusTrap'
import SvIcon from './SvIcon.vue'

const open = computed(() => !!dialogState.value)
const boxEl = ref<HTMLElement | null>(null)
useFocusTrap(boxEl, open)

let counted = false
watch(
  open,
  v => {
    if (v && !counted) {
      overlayDepth.value += 1
      counted = true
    } else if (!v && counted) {
      overlayDepth.value -= 1
      counted = false
    }
  },
  { immediate: true },
)
onUnmounted(() => {
  if (counted) overlayDepth.value -= 1
})

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
        <div
          ref="boxEl"
          class="box"
          role="alertdialog"
          aria-modal="true"
          :aria-label="dialogState.title"
          :aria-describedby="dialogState.message ? 'sv-dialog-msg' : undefined"
          tabindex="-1"
        >
          <b>{{ dialogState.title }}</b>
          <p v-if="dialogState.message" id="sv-dialog-msg">{{ dialogState.message }}</p>
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
  width: min(400px, 86vw);
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-sheet);
  text-align: left;
  overflow: hidden;
  box-shadow: var(--sv-elev-1);
  padding: 20px 20px 16px;
}
.box b {
  display: block;
  font-size: var(--sv-fs-title3);
  font-weight: 600;
}
.box p {
  margin-top: 8px;
  font-size: var(--sv-fs-body);
  color: var(--sv-label2);
  line-height: var(--sv-lh-body);
}
.btns {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}
.btns button {
  min-height: 32px;
  padding: 5px 14px;
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-ctl);
  background: var(--sv-card);
  cursor: pointer;
  font-size: var(--sv-fs-subhead);
  color: var(--sv-label2);
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.btns .ok {
  background: var(--sv-accent);
  border-color: var(--sv-accent);
  color: var(--sv-on-accent);
}
.btns .ok.danger {
  background: var(--sv-danger);
  border-color: var(--sv-danger);
  color: var(--sv-on-accent);
}
.dlg-enter-active,
.dlg-leave-active {
  transition: opacity 0.18s ease;
}
.dlg-enter-from,
.dlg-leave-to {
  opacity: 0;
}
</style>
