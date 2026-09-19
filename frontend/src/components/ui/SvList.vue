<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
}>(), {})
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
  font-size: var(--sv-fs-caption1); color: var(--sv-label2);
  margin: 18px 6px 6px; letter-spacing: 0.5px;
}
.sv-list {
  background: var(--sv-card);
  border-radius: var(--sv-r-card);
  overflow: hidden;
  box-shadow: var(--sv-sh-1);
}
[data-theme="dark"] .sv-list { border: 1px solid var(--sv-sep); box-shadow: none; }
</style>
