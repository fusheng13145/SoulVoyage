<script setup lang="ts">
import { vHaptic } from '@/directives/haptic'

withDefaults(defineProps<{ pad?: boolean }>(), { pad: true })
</script>

<template>
  <section v-haptic class="sv-card" :class="{ nopad: !pad }">
    <slot />
  </section>
</template>

<style scoped>
.sv-card {
  background: var(--sv-card);
  border-radius: var(--sv-r-card);
  box-shadow: var(--sv-sh-1);
  margin-bottom: 14px;
}
[data-theme='dark'] .sv-card {
  border: 1px solid var(--sv-sep);
  box-shadow: none;
}
.sv-card:not(.nopad) {
  padding: var(--sv-s4);
}
</style>
