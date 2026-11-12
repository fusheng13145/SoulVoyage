<script setup lang="ts">
import { vHaptic } from '@/directives/haptic'

withDefaults(
  defineProps<{
    role: 'ai' | 'me' | 'sys' | 'typing'
    name?: string
  }>(),
  { name: '' },
)
</script>

<template>
  <!-- 纸面气泡：me=苔绿填充、ai=1px 边框、sys=居中灰字、typing=三点脉冲 -->
  <div v-if="role === 'typing'" class="msg typing" role="status" aria-label="对方正在输入">
    <i /><i /><i />
  </div>
  <div v-else-if="role === 'sys'" class="msg sys">{{ name || '' }}<slot /></div>
  <div v-else v-haptic class="msg" :class="role">
    <small v-if="name && role === 'ai'" class="who">{{ name }}</small>
    <slot />
  </div>
</template>

<style scoped>
.msg {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: var(--sv-r-card);
  font-size: var(--sv-fs-body);
  line-height: var(--sv-lh-body);
  animation: rise 0.18s ease;
  word-break: break-word;
  white-space: pre-wrap;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
.msg.ai {
  align-self: flex-start;
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  color: var(--sv-label);
}
.msg.me {
  align-self: flex-end;
  color: var(--sv-on-accent);
  background: var(--sv-accent);
}
.msg.sys {
  align-self: center;
  background: transparent;
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption1);
  max-width: 90%;
  text-align: center;
}
.who {
  display: block;
  font-size: var(--sv-fs-caption2);
  color: var(--sv-label3);
  margin-bottom: 2px;
}
.typing {
  align-self: flex-start;
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  padding: 12px 14px;
  display: flex;
  gap: 4px;
}
.typing i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--sv-label3);
  animation: blink 1.2s infinite;
}
.typing i:nth-child(2) {
  animation-delay: 0.15s;
}
.typing i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes blink {
  0%,
  60%,
  100% {
    opacity: 0.35;
  }
  30% {
    opacity: 1;
  }
}
</style>
