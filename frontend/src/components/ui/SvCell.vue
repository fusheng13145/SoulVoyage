<script setup lang="ts">
import SvIcon from './SvIcon.vue'
import { vHaptic } from '@/directives/haptic'

withDefaults(
  defineProps<{
    label?: string
    hint?: string
    icon?: string
    tone?: string // 图标底色
    to?: string // router-link 目标
    danger?: boolean
    chevron?: boolean
  }>(),
  { chevron: true, label: '', hint: '', icon: '', tone: '', to: '' },
)
</script>

<template>
  <component
    :is="to ? 'router-link' : 'div'"
    v-haptic
    :to="to"
    class="cell"
    :class="{ danger }"
    :style="to ? { color: 'inherit' } : {}"
  >
    <span v-if="icon || $slots.icon" class="ico">
      <slot name="icon"><SvIcon :name="icon || 'i-sparkle'" :size="20" tone="inherit" /></slot>
    </span>
    <span class="body">
      <b>{{ label }}</b>
      <small v-if="hint || $slots.hint"
        ><slot name="hint">{{ hint }}</slot></small
      >
      <slot />
    </span>
    <span v-if="$slots.extra" class="extra"><slot name="extra" /></span>
    <SvIcon v-if="chevron && !danger" name="i-arrow" :size="16" class="arr" tone="label2" />
  </component>
</template>

<style scoped>
.cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--sv-sep);
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
  transition: background 0.15s;
}
.cell:last-child {
  border-bottom: none;
}
.ico {
  width: 24px;
  height: 24px;
  flex: none;
  display: grid;
  place-items: center;
  color: var(--sv-accent);
  background: transparent;
}
.body {
  flex: 1;
  min-width: 0;
}
.body b {
  font-weight: 400;
}
.body small {
  display: block;
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
}
.danger {
  color: var(--sv-danger);
  font-weight: 500;
}
.arr {
  margin-left: auto;
}
</style>
