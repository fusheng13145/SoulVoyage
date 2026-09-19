<script setup lang="ts">
withDefaults(defineProps<{
  modelValue?: boolean
  tone?: string        // 自定义底色（如情绪色/成就色），缺省为 fill2
  color?: string
}>(), { modelValue: false })

const emit = defineEmits<{ 'update:modelValue': [boolean]; click: [] }>()
</script>

<template>
  <button
    class="chip" :class="{ on: modelValue }"
    :style="modelValue && tone ? { background: tone, color: color || '#fff' } : (tone && !modelValue ? { background: tone, color: color || 'var(--sv-label)' } : {})"
    role="switch" :aria-checked="modelValue"
    @click="emit('update:modelValue', !modelValue); emit('click')"
  >
    <slot />
  </button>
</template>

<style scoped>
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 5px 12px; border-radius: var(--sv-r-pill); border: none;
  font-size: var(--sv-fs-footnote); cursor: pointer; font-family: inherit;
  background: var(--sv-fill2); color: var(--sv-label2);
  transition: background 0.2s, color 0.2s, transform 0.12s var(--sv-ease);
}
.chip:active { transform: scale(0.96); }
.chip.on { background: var(--sv-indigo); color: #fff; }
</style>
