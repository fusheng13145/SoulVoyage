<script setup lang="ts">
import { computed, ref } from 'vue'
import http, { type ApiResp } from '../api/http'
import { streamTask } from '../api/sse'
import AgentFlowProgress from '../components/AgentFlowProgress.vue'

interface Step { agent: string; stepSeq: number; state: 'pending'|'running'|'done'|'degraded'|'failed' }

const text = ref('')
const submitting = ref(false)
const steps = ref<Step[]>([])
const results = ref<Record<string, any>>({})
const error = ref('')

const emotion = computed(() => results.value.EMOTION ?? null)
const trace = computed(() => results.value.TRACE ?? null)

async function save() {
  if (!text.value.trim() || submitting.value) return
  submitting.value = true
  error.value = ''
  results.value = {}
  steps.value = [
    { agent: 'EMOTION', stepSeq: 1, state: 'pending' },
    { agent: 'TRACE', stepSeq: 2, state: 'pending' },
  ]
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
          results.value = { ...results.value, [e.data.agent]: e.data.payload }
          mark(e.data.agent, 'done'); break
        case 'step_failed':
          mark(e.data.agent, 'degraded'); break
        case 'done':
          if (e.data.status === 'FAILED') error.value = '任务未完成，请稍后重试'
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

const REPORT_TITLES: Record<string, string> = {
  eventSummary: '事件', emotionSummary: '情绪', thoughtSummary: '想法',
  insight: '洞察', suggestion: '下一步',
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

      <div v-if="emotion" class="result">
        <h3>今日情绪：{{ EMOJI[emotion.primaryEmotion] || '🙂' }} {{ emotion.primaryEmotion }}</h3>
        <div class="bars">
          <div class="bar">强度
            <div class="track"><div class="fill neg" :style="{ width: emotion.intensity * 100 + '%' }" /></div>
            <span>{{ Math.round(emotion.intensity * 100) }}%</span>
          </div>
          <div class="bar">效价
            <div class="track"><div class="fill" :class="emotion.valence < 0 ? 'neg' : 'pos'"
              :style="{ width: Math.abs(emotion.valence) * 100 + '%' }" /></div>
            <span>{{ emotion.valence }}</span>
          </div>
        </div>
        <ul class="tags">
          <li v-for="t in emotion.eventTags" :key="t.tag">
            <b>{{ t.tag }}</b><em v-if="t.evidence">「{{ t.evidence }}」</em>
          </li>
        </ul>
      </div>

      <div v-if="trace" class="result">
        <h3>溯源复盘</h3>
        <p v-if="trace.insufficientEvidence" class="hint">这次记录有点少，先不下结论，想到什么再补一句。</p>

        <template v-if="trace.stressors?.length">
          <h4>可能的压力来源</h4>
          <ul class="stressors">
            <li v-for="s in trace.stressors" :key="s.source">
              <b>{{ s.source }}</b>
              <span class="conf">{{ Math.round(s.confidence * 100) }}%</span>
              <em v-for="(ev, i) in s.evidence" :key="i">「{{ ev }}」</em>
            </li>
          </ul>
        </template>

        <template v-if="trace.cognitiveDistortions?.length">
          <h4>思维误区线索（源自心理学知识库）</h4>
          <div v-for="d in trace.cognitiveDistortions" :key="d.kgNodeId" class="card">
            <b>{{ d.name }}</b>
            <p class="trigger">触发：{{ d.trigger }}</p>
            <p class="q">🤔 {{ d.challengeQuestion }}</p>
          </div>
        </template>

        <template v-if="trace.socraticQuestions?.length">
          <h4>留给你慢慢想的问题</h4>
          <ol class="socratic">
            <li v-for="(q, i) in trace.socraticQuestions" :key="i">{{ q }}</li>
          </ol>
        </template>

        <h4>事件-想法-情绪复盘</h4>
        <dl class="report">
          <template v-for="(v, k) in trace.report" :key="k">
            <dt>{{ REPORT_TITLES[k as string] || k }}</dt><dd>{{ v }}</dd>
          </template>
        </dl>
        <p class="disclaimer">以上为自助梳理参考，不构成任何诊断；如需专业帮助，可拨打 12356 心理援助热线或联系学校心理中心。</p>
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
.result h4 { margin: 16px 0 8px; color: #40466b; font-size: 14px; }
.bars { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.bar { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #5c6483; }
.track { flex: 1; height: 8px; background: #eef1fa; border-radius: 4px; overflow: hidden; max-width: 320px; }
.fill { height: 100%; border-radius: 4px; } .fill.neg { background: #f0a35e; } .fill.pos { background: #6fd0a8; }
.tags { margin: 0; padding-left: 20px; color: #40466b; font-size: 14px; }
.tags em { color: #8a93b5; font-size: 12px; margin-left: 6px; }
.hint { color: #8a93b5; font-size: 13px; }
.stressors { margin: 0; padding-left: 20px; color: #40466b; font-size: 14px; }
.stressors em { display: block; color: #8a93b5; font-size: 12px; }
.conf { margin-left: 8px; font-size: 12px; color: #5b6cff; background: #eef0ff; border-radius: 8px; padding: 1px 6px; }
.card { border: 1px solid #e8ecf8; border-radius: 10px; padding: 10px 12px; margin-bottom: 8px; }
.card b { color: #40466b; }
.trigger { margin: 6px 0 4px; font-size: 13px; color: #7c84a6; }
.q { margin: 0; font-size: 14px; color: #4a5170; }
.socratic { margin: 0; padding-left: 22px; color: #4a5170; font-size: 14px; line-height: 1.7; }
.report { margin: 0; font-size: 14px; }
.report dt { float: left; clear: left; width: 5em; color: #8a93b5; }
.report dd { margin: 0 0 8px 5.5em; color: #40466b; }
.disclaimer { margin: 14px 0 0; font-size: 12px; color: #9aa1bd; border-top: 1px dashed #e8ecf8; padding-top: 10px; }
</style>
