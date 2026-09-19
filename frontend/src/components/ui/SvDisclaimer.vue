<script setup lang="ts">
import { ref } from 'vue'
import SvIcon from './SvIcon.vue'

withDefaults(defineProps<{
  text?: string
  reason?: string      // 「为什么这么说」展开内容（可露出 KG 出处）
}>(), {
  text: '本内容为自助参考，不构成医学诊断',
})
const open = ref(false)
</script>

<template>
  <div class="disc">
    <p>
      {{ text }}
      <button v-if="reason" class="why" :aria-expanded="open" @click="open = !open">
        为什么这么说 <SvIcon name="i-arrow" :size="12" :style="{ transform: open ? 'rotate(-90deg)' : 'rotate(90deg)' }" />
      </button>
    </p>
    <p v-if="open && reason" class="detail">{{ reason }}</p>
  </div>
</template>

<style scoped>
.disc {
  background: color-mix(in srgb, var(--sv-amber) 14%, var(--sv-bg));
  border: 1px solid color-mix(in srgb, var(--sv-amber) 40%, transparent);
  font-size: var(--sv-fs-caption2);
  padding: 8px 12px; border-radius: 12px; margin-top: 4px; color: var(--sv-label);
}
.why {
  border: none; background: transparent; cursor: pointer; color: var(--sv-indigo);
  font-size: inherit; font-family: inherit; padding: 2px 4px;
  display: inline-flex; align-items: center; gap: 2px;
}
.why :deep(svg) { transition: transform 0.25s var(--sv-ease); }
.detail { margin-top: 6px; line-height: 1.6; color: var(--sv-label2); }
</style>
