<script setup lang="ts">
defineProps<{ modelValue: boolean; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <button
    class="sw" :class="{ on: modelValue }" role="switch"
    :aria-checked="modelValue" :aria-label="label"
    @click="emit('update:modelValue', !modelValue)"
  ><span class="knob" /></button>
</template>

<style scoped>
.sw {
  width: 51px; height: 31px; border-radius: var(--sv-r-pill); flex: none;
  background: var(--sv-label3); border: none; padding: 0; position: relative;
  cursor: pointer; transition: background 0.25s;
}
.sw.on { background: var(--sv-mint); }
.knob {
  position: absolute; top: 2px; left: 2px; width: 27px; height: 27px;
  border-radius: 50%; background: #fff; box-shadow: 0 2px 5px rgba(0, 0, 0, 0.25);
  transition: left 0.25s var(--sv-ease);
}
.sw.on .knob { left: 22px; }
</style>
