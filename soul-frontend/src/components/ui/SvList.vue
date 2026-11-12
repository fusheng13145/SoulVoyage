<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
  }>(),
  { title: '' },
)
</script>
<template>
  <div class="group">
    <p v-if="title || $slots.title" class="sec">
      <slot name="title">{{ title }}</slot>
    </p>
    <div class="sv-list">
      <slot />
    </div>
  </div>
</template>
<style scoped>
.sec {
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label3);
  margin: 20px 6px 6px;
  letter-spacing: 0;
}
.sv-list {
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  border-radius: var(--sv-r-card);
  overflow: hidden;
}
</style>
