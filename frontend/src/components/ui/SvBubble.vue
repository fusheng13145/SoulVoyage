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
  <!-- iMessage 质感气泡（DS3）：me=渐变蓝、ai=描边卡片、sys=居中灰字、typing=三点指示 -->
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
  padding: 10px 14px;
  border-radius: 20px;
  font-size: var(--sv-fs-callout);
  line-height: 1.5;
  animation: pop 0.3s var(--sv-ease);
  word-break: break-word;
  white-space: pre-wrap;
}
@keyframes pop {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.96);
  }
  to {
    opacity: 1;
  }
}
.msg.ai {
  align-self: flex-start;
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  border-bottom-left-radius: 6px;
  color: var(--sv-label);
}
.msg.me {
  align-self: flex-end;
  color: #fff;
  background: linear-gradient(135deg, var(--sv-blue), var(--sv-indigo));
  border-bottom-right-radius: 6px;
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
  opacity: 0.65;
  margin-bottom: 2px;
}
.typing {
  align-self: flex-start;
  background: var(--sv-card);
  border: 1px solid var(--sv-sep);
  border-radius: 20px;
  padding: 12px 16px;
  display: flex;
  gap: 4px;
}
.typing i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sv-label3);
  animation: bounce 1.2s infinite;
}
.typing i:nth-child(2) {
  animation-delay: 0.15s;
}
.typing i:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes bounce {
  0%,
  60%,
  100% {
    transform: none;
  }
  30% {
    transform: translateY(-5px);
  }
}
</style>
