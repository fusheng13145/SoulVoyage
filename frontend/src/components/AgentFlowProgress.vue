<script setup lang="ts">
interface FlowStep {
  agent: string
  stepSeq: number
  state: 'pending' | 'running' | 'done' | 'degraded' | 'failed'
}

const props = defineProps<{ steps: FlowStep[] }>()
void props

const LABELS: Record<string, string> = {
  EMOTION: '情绪感知',
  TRACE: '溯源推理',
  SIMULATE: '心智训练',
  SUPPORT: '疏导干预',
  RISK_ARCHIVE: '风险归档',
}

const icon = (s: FlowStep['state']) =>
  ({ pending: '○', running: '◐', done: '✓', degraded: '△', failed: '✕' }[s])
</script>

<template>
  <div class="flow">
    <div v-for="(s, i) in steps" :key="s.stepSeq" class="node" :class="s.state">
      <span class="dot">{{ icon(s.state) }}</span>
      <span class="label">{{ LABELS[s.agent] || s.agent }}</span>
      <span v-if="i < steps.length - 1" class="arrow">→</span>
    </div>
  </div>
</template>

<style scoped>
.flow { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; padding: 10px 14px; background: #fff; border-radius: 10px; box-shadow: 0 1px 6px rgba(80,90,160,.08); }
.node { display: flex; align-items: center; gap: 6px; color: #9aa1bd; font-size: 14px; }
.node.running { color: #5b6cff; } .node.running .dot { animation: spin 1.2s linear infinite; display:inline-block; }
.node.done { color: #34a06a; } .node.degraded { color: #d99127; } .node.failed { color: #d4574e; }
.arrow { margin: 0 8px; color: #c8cee4; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
