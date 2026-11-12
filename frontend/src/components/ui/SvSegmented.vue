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
      v-for="o in options"
      :key="String(o.value)"
      role="tab"
      :aria-selected="modelValue === o.value"
      class="opt"
      :class="{ on: modelValue === o.value }"
      @click="emit('update:modelValue', o.value)"
    >
      {{ o.label }}
    </button>
  </div>
</template>

<style scoped>
.seg {
  display: flex;
  gap: var(--sv-s4);
  background: transparent;
  border-bottom: 1px solid var(--sv-sep);
}
.opt {
  flex: 0 0 auto;
  min-height: 32px;
  border: none;
  cursor: pointer;
  background: transparent;
  border-bottom: 1px solid transparent;
  margin-bottom: -1px;
  font-size: var(--sv-fs-subhead);
  font-weight: 400;
  color: var(--sv-label2);
  font-family: inherit;
  transition:
    color 0.18s,
    border-color 0.18s;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 2px;
}
.opt.on {
  color: var(--sv-accent);
  border-bottom-color: var(--sv-accent);
  font-weight: 500;
}
</style>
