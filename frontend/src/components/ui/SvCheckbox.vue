<script setup lang="ts">
import SvIcon from './SvIcon.vue'

defineProps<{ modelValue: boolean; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <button
    class="cb"
    :class="{ on: modelValue }"
    role="checkbox"
    :aria-checked="modelValue"
    :aria-label="label"
    @click="emit('update:modelValue', !modelValue)"
  >
    <SvIcon v-if="modelValue" name="i-check" :size="14" tone="inherit" />
  </button>
</template>

<style scoped>
.cb {
  width: 20px;
  height: 20px;
  border-radius: var(--sv-r-tag);
  flex: none;
  cursor: pointer;
  border: 1px solid var(--sv-line-strong);
  background: var(--sv-card2);
  display: grid;
  place-items: center;
  color: var(--sv-on-accent);
  transition:
    background 0.18s,
    border-color 0.18s;
}
.cb.on {
  background: var(--sv-accent);
  border-color: var(--sv-accent);
}
</style>
