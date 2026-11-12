<script setup lang="ts">
withDefaults(
  defineProps<{
    name: string
    size?: number
    tone?: 'label' | 'label2' | 'inherit'
  }>(),
  { size: 22, tone: 'label' },
)
</script>

<template>
  <svg
    aria-hidden="true"
    :width="size"
    :height="size"
    :style="{ color: tone === 'label2' ? 'var(--sv-label2)' : 'inherit', flex: 'none' }"
  >
    <use :href="`/icons.svg#${name}`" />
  </svg>
</template>
