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
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: none;
  cursor: pointer;
  border: 1.8px solid var(--sv-label3);
  background: transparent;
  display: grid;
  place-items: center;
  color: #fff;
  transition:
    background 0.2s,
    border-color 0.2s;
}
.cb.on {
  background: var(--sv-mint);
  border-color: var(--sv-mint);
}
</style>
