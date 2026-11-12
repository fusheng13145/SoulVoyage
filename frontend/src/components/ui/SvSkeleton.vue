<script setup lang="ts">
withDefaults(defineProps<{ h?: number; w?: string; round?: boolean }>(), { h: 14, w: '', round: false })
</script>

<template>
  <span
    class="sk"
    aria-hidden="true"
    :style="{
      height: h + 'px',
      width: w || '100%',
      borderRadius: round ? '50%' : 'var(--sv-r-ctl)',
    }"
  />
</template>

<style scoped>
.sk {
  display: block;
  background: var(--sv-fill2);
  animation: breathe 1.4s ease-in-out infinite;
}
@keyframes breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}
</style>
