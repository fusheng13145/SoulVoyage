<script setup lang="ts">
import { ref } from 'vue'
import http, { type ApiResp } from '../api/http'
import { streamTask } from '../api/sse'
import AgentFlowProgress from '../components/AgentFlowProgress.vue'

interface Step { agent: string; stepSeq: number; state: 'pending'|'running'|'done'|'degraded'|'failed' }

const text = ref('')
const submitting = ref(false)
const steps = ref<Step[]>([])
const result = ref<any>(null)
const error = ref('')

async function save() {
  if (!text.value.trim() || submitting.value) return
  submitting.value = true
  error.value = ''
  result.value = null
  steps.value = [{ agent: 'EMOTION', stepSeq: 1, state: 'pending' }]
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>('/tasks', {
      pipelineCode: 'DIARY_PIPELINE',
      payload: { diaryText: text.value, recordDate: new Date().toISOString().slice(0, 10) },
      clientReqId: `web-${Date.now()}`,
    })
    await streamTask(data.data.taskNo, (e) => {
      switch (e.event) {
        case 'step_started':
          mark(e.data.agent, 'running'); break
        case 'middle_result':
          mark(e.data.agent, 'done'); break
        case 'step_failed':
          mark(e.data.agent, 'degraded'); break
        case 'done':
          if (e.data.status === 'SUCCESS' || e.data.status === 'PARTIAL_SUCCESS') result.value = e.data.payload
          else error.value = '任务未完成，请稍后重试'
          submitting.value = false
          break
        case 'error':
          error.value = e.data.message || '服务异常'; submitting.value = false
      }
    })
  } catch (e: any) {
    error.value = e.message || '提交失败'
  } finally {
    submitting.value = false
  }
}

function mark(agent: string, state: Step['state']) {
  const s = steps.value.find(x => x.agent === agent)
  if (s) s.state = state
}

const EMOJI: Record<string, string> = {
  愤怒: '😠', 焦虑: '😰', 悲伤: '😢', 喜悦: '😊', 平静: '😌', 麻木: '😶', 压力: '😮‍💨', 孤独: '🥀',
}
</script>

<template>
  <div class="diary">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>情绪日记</b>
    </header>
    <main>
      <textarea v-model="text" rows="7"
        placeholder="今天发生了什么？你感觉怎么样？写下来，让心屿陪你梳理一下……（内容为自助参考，不构成医学诊断）"></textarea>
      <AgentFlowProgress v-if="steps.length" :steps="steps" class="flow" />
      <button class="primary" :disabled="submitting || !text.trim()" @click="save">
        {{ submitting ? '多 Agent 处理中…' : '交给心屿梳理' }}
      </button>
      <p v-if="error" class="err">{{ error }}</p>

      <div v-if="result" class="result">
        <h3>今日情绪：{{ EMOJI[result.primaryEmotion] || '🙂' }} {{ result.primaryEmotion }}</h3>
        <div class="bars">
          <div class="bar">强度
            <div class="track"><div class="fill neg" :style="{ width: result.intensity * 100 + '%' }" /></div>
            <span>{{ Math.round(result.intensity * 100) }}%</span>
          </div>
          <div class="bar">效价
            <div class="track"><div class="fill" :class="result.valence < 0 ? 'neg' : 'pos'"
              :style="{ width: Math.abs(result.valence) * 100 + '%' }" /></div>
            <span>{{ result.valence }}</span>
          </div>
        </div>
        <ul class="tags">
          <li v-for="t in result.eventTags" :key="t.tag">
            <b>{{ t.tag }}</b><em v-if="t.evidence">「{{ t.evidence }}」</em>
          </li>
        </ul>
        <p class="next">M2 更新后，这里会追加：压力源推理、认知误区识别与复盘报告。</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.diary { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
main { max-width: 720px; margin: 24px auto; padding: 0 16px; }
textarea { width: 100%; border: 1px solid #dde3f3; border-radius: 12px; padding: 14px; font-size: 15px; resize: vertical; font-family: inherit; }
.flow { margin: 14px 0; }
.primary { width: 100%; padding: 12px; background: #5b6cff; color: #fff; border: 0; border-radius: 10px; font-size: 15px; cursor: pointer; }
.primary:disabled { opacity: .55; cursor: not-allowed; }
.err { color: #d4574e; }
.result { margin-top: 18px; background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); }
.result h3 { margin: 0 0 12px; }
.bars { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.bar { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #5c6483; }
.track { flex: 1; height: 8px; background: #eef1fa; border-radius: 4px; overflow: hidden; max-width: 320px; }
.fill { height: 100%; border-radius: 4px; } .fill.neg { background: #f0a35e; } .fill.pos { background: #6fd0a8; }
.tags { margin: 0; padding-left: 20px; color: #40466b; font-size: 14px; }
.tags em { color: #8a93b5; font-size: 12px; margin-left: 6px; }
.next { color: #9aa1bd; font-size: 12px; margin-bottom: 0; }
</style>
