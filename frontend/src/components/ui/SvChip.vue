<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    tone?: string // 自定义底色（如情绪色/成就色），缺省为 fill2
    color?: string
  }>(),
  { modelValue: false, tone: '', color: '' },
)

const emit = defineEmits<{ 'update:modelValue': [boolean]; click: [] }>()

function toggle() {
  emit('update:modelValue', !props.modelValue)
  emit('click')
}

const styleObj = computed(() =>
  props.modelValue && props.tone
    ? { background: props.tone, color: props.color || '#fff' }
    : props.tone && !props.modelValue
      ? { background: props.tone, color: props.color || 'var(--sv-label)' }
      : {},
)
</script>

<template>
  <button
    class="chip"
    :class="{ on: modelValue }"
    :style="styleObj"
    role="switch"
    :aria-checked="modelValue"
    @click="toggle"
  >
    <slot />
  </button>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border-radius: var(--sv-r-pill);
  border: none;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  background: var(--sv-fill2);
  color: var(--sv-label2);
  transition:
    background 0.2s,
    color 0.2s,
    transform 0.12s var(--sv-ease);
}
.chip:active {
  transform: scale(0.96);
}
.chip.on {
  background: var(--sv-indigo);
  color: #fff;
}
</style>
