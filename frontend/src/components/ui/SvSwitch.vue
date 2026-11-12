<script setup lang="ts">
defineProps<{ modelValue: boolean; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <button
    class="sw"
    :class="{ on: modelValue }"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="label"
    @click="emit('update:modelValue', !modelValue)"
  >
    <span class="knob" />
  </button>
</template>

<style scoped>
.sw {
  width: 44px;
  height: 26px;
  border-radius: var(--sv-r-ctl);
  flex: none;
  background: var(--sv-fill2);
  border: 1px solid var(--sv-line-strong);
  padding: 0;
  position: relative;
  cursor: pointer;
  transition:
    background 0.18s,
    border-color 0.18s;
}
.sw.on {
  background: var(--sv-accent-soft);
  border-color: var(--sv-accent);
}
.knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 20px;
  height: 20px;
  border-radius: var(--sv-r-tag);
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  transition: left 0.18s ease;
}
.sw.on .knob {
  left: 20px;
  background: var(--sv-accent);
  border-color: var(--sv-accent);
}
</style>
