<script setup lang="ts">
defineProps<{
  modelValue: string | number
  options: { label: string; value: string | number }[]
}>()
const emit = defineEmits<{ 'update:modelValue': [string | number] }>()
</script>

<template>
  <div class="seg" role="tablist">
    <button
      v-for="o in options" :key="String(o.value)"
      role="tab" :aria-selected="modelValue === o.value"
      class="opt" :class="{ on: modelValue === o.value }"
      @click="emit('update:modelValue', o.value)"
    >{{ o.label }}</button>
  </div>
</template>

<style scoped>
.seg {
  display: flex; padding: 2px; gap: 2px;
  background: var(--sv-fill2); border-radius: 10px;
}
.opt {
  flex: 1; min-height: 32px; border: none; cursor: pointer;
  background: transparent; border-radius: 8px;
  font-size: var(--sv-fs-subhead); font-weight: 500; color: var(--sv-label);
  font-family: inherit; transition: background 0.25s var(--sv-ease);
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
}
.opt.on { background: var(--sv-card); font-weight: 600; box-shadow: 0 1px 4px rgba(0, 0, 0, 0.12); }
</style>
