<script setup lang="ts">
import SvIcon from './SvIcon.vue'

export interface FlowStep {
  agent: string
  stepSeq: number
  state: 'pending' | 'running' | 'done' | 'degraded' | 'failed'
  sub?: string
  /** running 节点滚动字幕：消费 middle_result/心跳摘要 */
  detail?: string
}

const LABELS: Record<string, string> = {
  EMOTION: '情绪感知',
  TRACE: '溯源推理',
  SIMULATE: '心智训练',
  SUPPORT: '疏导干预',
  RISK_ARCHIVE: '风险归档',
}
const STATE_TEXT: Record<FlowStep['state'], string> = {
  pending: '等待中',
  running: '进行中',
  done: '已完成',
  degraded: '部分完成',
  failed: '失败',
}

withDefaults(
  defineProps<{
    steps: FlowStep[]
    reconnectNote?: string
  }>(),
  { reconnectNote: '' },
)

const emit = defineEmits<{ toggle: [agent: string] }>()
</script>

<template>
  <div
    class="tl-wrap"
    role="list"
    :aria-label="'多 Agent 处理进度' + (reconnectNote ? '；' + reconnectNote : '')"
  >
    <p v-if="reconnectNote" class="reconn"><SvIcon name="i-refresh" :size="14" /> {{ reconnectNote }}</p>
    <ol class="tl">
      <li v-for="s in steps" :key="s.agent" role="listitem" class="node" :class="s.state">
        <span class="n" aria-hidden="true">
          <SvIcon v-if="s.state === 'done'" name="i-check" :size="10" tone="inherit" />
          <SvIcon v-else-if="s.state === 'degraded'" name="i-alert" :size="10" tone="inherit" />
        </span>
        <button
          class="cap"
          :class="{ clickable: s.state === 'done' || s.state === 'degraded' }"
          :aria-expanded="s.state === 'done' ? true : undefined"
          @click="emit('toggle', s.agent)"
        >
          <b>{{ LABELS[s.agent] || s.agent }}</b>
          <small class="sub">{{ s.sub || STATE_TEXT[s.state] }}</small>
          <span v-if="s.state === 'running' && s.detail" class="marquee">{{ s.detail }}</span>
        </button>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.tl-wrap {
  position: relative;
}
.reconn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  margin-bottom: 6px;
}
.tl {
  position: relative;
  padding-left: 26px;
}
.tl::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 8px;
  bottom: 8px;
  width: 1px;
  background: var(--sv-sep);
}
.node {
  position: relative;
  margin-bottom: 14px;
  opacity: 0.4;
  transition: opacity 0.18s;
}
.node.done,
.node.running,
.node.degraded,
.node.failed {
  opacity: 1;
}
.n {
  position: absolute;
  left: -26px;
  top: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--sv-card);
  border: 1px solid var(--sv-label3);
  display: grid;
  place-items: center;
  color: var(--sv-label2);
  z-index: 1;
}
.done .n {
  background: var(--sv-accent);
  border-color: var(--sv-accent);
  color: var(--sv-on-accent);
}
.degraded .n {
  background: var(--sv-warn);
  border-color: var(--sv-warn);
  color: var(--sv-on-accent);
}
.failed .n {
  background: var(--sv-danger);
  border-color: var(--sv-danger);
  color: var(--sv-on-accent);
}
/* running：不发光，只做轻微呼吸 */
.running .n {
  border-color: var(--sv-accent);
}
.running .n::after {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--sv-accent);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
.cap {
  border: none;
  background: transparent;
  padding: 0;
  cursor: default;
  text-align: left;
  font-family: inherit;
  display: block;
}
.cap b {
  font-size: var(--sv-fs-subhead);
  font-weight: 500;
  color: var(--sv-label);
  display: block;
}
.cap.clickable {
  cursor: pointer;
}
.cap.clickable b {
  color: var(--sv-accent);
}
.sub {
  display: block;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label2);
  margin-top: 2px;
}
.marquee {
  display: block;
  font-size: var(--sv-fs-caption1);
  color: var(--sv-accent);
  margin-top: 2px;
  max-width: 56ch;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
