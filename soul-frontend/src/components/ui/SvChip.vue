<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    tone?: string // 兼容旧调用：迁移期不再作为底色
    color?: string
  }>(),
  { modelValue: false, tone: '', color: '' },
)

const emit = defineEmits<{ 'update:modelValue': [boolean]; click: [] }>()

function toggle() {
  emit('update:modelValue', !props.modelValue)
  emit('click')
}
</script>

<template>
  <button class="chip" :class="{ on: modelValue }" role="switch" :aria-checked="modelValue" @click="toggle">
    <slot />
  </button>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: var(--sv-r-tag);
  border: 1px solid var(--sv-sep);
  font-size: var(--sv-fs-caption1);
  cursor: pointer;
  font-family: inherit;
  background: transparent;
  color: var(--sv-label2);
  transition:
    background 0.18s,
    color 0.18s,
    border-color 0.18s;
}
.chip.on {
  background: var(--sv-accent-soft);
  border-color: var(--sv-accent);
  color: var(--sv-accent);
}
</style>
