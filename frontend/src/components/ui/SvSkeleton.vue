<script setup lang="ts">
withDefaults(defineProps<{ h?: number; w?: string; round?: boolean }>(), { h: 14, w: '', round: false })
</script>

<template>
  <span
    class="sk"
    :style="{
      height: h + 'px',
      width: w || '100%',
      borderRadius: round ? '50%' : '14px',
    }"
  />
</template>

<style scoped>
.sk {
  display: block;
  background: linear-gradient(
    90deg,
    var(--sv-fill2) 25%,
    color-mix(in srgb, var(--sv-fill2) 40%, var(--sv-card)) 50%,
    var(--sv-fill2) 75%
  );
  background-size: 200%;
  animation: shim 1.3s infinite;
}
@keyframes shim {
  to {
    background-position: -200%;
  }
}
</style>
