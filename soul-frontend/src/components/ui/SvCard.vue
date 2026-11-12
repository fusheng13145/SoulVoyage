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
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-card);
  margin-bottom: 14px;
}
.sv-card:not(.nopad) {
  padding: var(--sv-s5);
}
</style>
