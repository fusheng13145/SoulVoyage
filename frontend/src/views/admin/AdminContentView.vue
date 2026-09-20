<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import SvCard from '@/components/ui/SvCard.vue'
import {
  listKg,
  listScenes,
  listExercises,
  upsertKgStatus,
  upsertSceneStatus,
  upsertExerciseStatus,
  refreshContent,
  previewPrompt,
  type KgNode,
  type SceneCard,
  type ExerciseItem,
  type PreviewResp,
} from '@/api/admin'
import { toast } from '@/stores/ui'

type Tab = 'kg' | 'scenes' | 'exercises'
const TABS: [Tab, string][] = [
  ['kg', '知识节点'],
  ['scenes', '场景卡'],
  ['exercises', '练习库'],
]
const tab = ref<Tab>('kg')
const kgType = ref('')
const loading = ref(true)
const kg = ref<KgNode[]>([])
const scenes = ref<SceneCard[]>([])
const exercises = ref<ExerciseItem[]>([])
const expanded = ref<string>('')

const KG_TYPES = ['DISTORTION', 'PSY_TOPIC', 'COMM_CASE', 'STRENGTH_TECH']
const KG_TYPE_LABEL: Record<string, string> = {
  DISTORTION: '误区',
  PSY_TOPIC: '科普',
  COMM_CASE: '案例',
  STRENGTH_TECH: '技巧',
}

async function load() {
  loading.value = true
  try {
    if (tab.value === 'kg') kg.value = await listKg(kgType.value || undefined)
    else if (tab.value === 'scenes') scenes.value = await listScenes()
    else exercises.value = await listExercises()
  } catch (e) {
    toast((e as Error).message || '内容没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)
function switchTab(t: Tab) {
  tab.value = t
  expanded.value = ''
  load()
}

async function toggleStatus(code: string, next: number) {
  const fn =
    tab.value === 'kg' ? upsertKgStatus : tab.value === 'scenes' ? upsertSceneStatus : upsertExerciseStatus
  try {
    const r = await fn(code, next)
    toast(`${code} 已${next === 1 ? '上架' : '下架'}，版本 v${r.contentVersion} 即生效`)
    load()
  } catch (e) {
    toast((e as Error).message || '操作失败')
  }
}

async function refresh() {
  try {
    const r = await refreshContent()
    toast(`内容快照已刷新（v${r.contentVersion}）`)
  } catch (e) {
    toast((e as Error).message || '刷新失败')
  }
}

function toggleExpand(key: string) {
  expanded.value = expanded.value === key ? '' : key
}
const pretty = (json: string | null | undefined) => {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

// —— A3 预览试跑 ——
const pvTemplate = ref('')
const pvUser = ref('')
const pvVars = ref('')
const pvBusy = ref(false)
const pvResult = ref<PreviewResp | null>(null)
async function preview() {
  if (!pvTemplate.value.trim() || !pvUser.value.trim()) {
    toast('需要模板名与 user 文本')
    return
  }
  let vars: Record<string, string> | undefined
  if (pvVars.value.trim()) {
    try {
      vars = JSON.parse(pvVars.value)
    } catch {
      toast('vars 需为 JSON 对象')
      return
    }
  }
  pvBusy.value = true
  pvResult.value = null
  try {
    pvResult.value = await previewPrompt({ template: pvTemplate.value.trim(), user: pvUser.value, vars })
  } catch (e) {
    toast((e as Error).message || '试跑失败')
  } finally {
    pvBusy.value = false
  }
}
</script>

<template>
  <AdminShell title="内容管理" subtitle="A3 · 热更新与预览试跑">
    <div class="topbar">
      <div class="seg">
        <button
          v-for="t in TABS"
          :key="t[0]"
          class="seg-btn"
          :class="{ on: tab === t[0] }"
          @click="switchTab(t[0])"
        >
          {{ t[1] }}
        </button>
      </div>
      <button class="btn" @click="refresh">强制刷新快照</button>
    </div>

    <select v-if="tab === 'kg'" v-model="kgType" class="filter" @change="load">
      <option value="">全部类型</option>
      <option v-for="t in KG_TYPES" :key="t" :value="t">{{ KG_TYPE_LABEL[t] }} {{ t }}</option>
    </select>

    <p v-if="loading" class="muted center">加载中…</p>
    <template v-else>
      <SvCard v-for="n in kg" :key="n.code" :pad="false" class="item">
        <button class="hd" @click="toggleExpand(n.code)">
          <span class="pill" :class="n.status === 1 ? 'ok' : 'off'">{{
            n.status === 1 ? '上架' : '下架'
          }}</span>
          <span class="t-txt"
            ><b>{{ n.name }}</b
            ><small>{{ n.code }} · {{ KG_TYPE_LABEL[n.type] || n.type }}</small></span
          >
          <span class="acts">
            <button class="btn tiny" @click.stop="toggleStatus(n.code, n.status === 1 ? 0 : 1)">
              {{ n.status === 1 ? '下架' : '上架' }}
            </button>
          </span>
        </button>
        <pre v-if="expanded === n.code" class="json">{{ pretty(n.payloadJson) }}</pre>
      </SvCard>

      <SvCard v-for="s in scenes" :key="s.code" :pad="false" class="item">
        <button class="hd" @click="toggleExpand(s.code)">
          <span class="pill" :class="s.status === 1 ? 'ok' : 'off'">{{
            s.status === 1 ? '上架' : '下架'
          }}</span>
          <span class="t-txt"
            ><b>{{ s.title }}</b
            ><small>{{ s.code }} · 标签 {{ s.tags || '—' }}</small></span
          >
          <span class="acts">
            <button class="btn tiny" @click.stop="toggleStatus(s.code, s.status === 1 ? 0 : 1)">
              {{ s.status === 1 ? '下架' : '上架' }}
            </button>
          </span>
        </button>
        <div v-if="expanded === s.code" class="detail">
          <p class="desc">{{ s.description }}</p>
          <pre class="json">{{ pretty(s.personaJson) }}</pre>
        </div>
      </SvCard>

      <SvCard v-for="x in exercises" :key="x.code" :pad="false" class="item">
        <button class="hd" @click="toggleExpand(x.code)">
          <span class="pill" :class="x.status === 1 ? 'ok' : 'off'">{{
            x.status === 1 ? '上架' : '下架'
          }}</span>
          <span class="t-txt"
            ><b>{{ x.name }}</b
            ><small>{{ x.code }} · {{ x.durationMin ?? '—' }} 分钟</small></span
          >
          <span class="acts">
            <button class="btn tiny" @click.stop="toggleStatus(x.code, x.status === 1 ? 0 : 1)">
              {{ x.status === 1 ? '下架' : '上架' }}
            </button>
          </span>
        </button>
        <pre v-if="expanded === x.code" class="json">{{ pretty(x.stepsJson) }}</pre>
      </SvCard>
    </template>

    <!-- A3 预览试跑 -->
    <SvCard class="pv">
      <h3>预览试跑（Prompt 模板）</h3>
      <input v-model="pvTemplate" class="in" placeholder="模板名（如 support / diary_summary）" />
      <textarea v-model="pvUser" class="in area" rows="2" placeholder="user 文本（模拟用户输入）" />
      <textarea
        v-model="pvVars"
        class="in area"
        rows="2"
        placeholder='vars JSON（可选，如 {"nickname":"小屿"}）'
      />
      <button class="btn primary" :disabled="pvBusy" @click="preview">
        {{ pvBusy ? '模型跑动中…' : '跑一次' }}
      </button>
      <div v-if="pvResult" class="pv-out">
        <p class="muted">
          {{ pvResult.template }} · {{ pvResult.model }} · {{ pvResult.costMs }}ms · token
          {{ pvResult.tokensIn }}/{{ pvResult.tokensOut }}
        </p>
        <pre class="json">{{ pvResult.system }}</pre>
        <pre class="json out">{{ pvResult.output }}</pre>
      </div>
    </SvCard>
  </AdminShell>
</template>

<style scoped>
.topbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: var(--sv-s4);
}
.seg {
  display: flex;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.seg-btn {
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label2);
  border-radius: 999px;
  padding: 7px 12px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.seg-btn.on {
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
  color: #fff;
}
.filter {
  width: 100%;
  margin-bottom: var(--sv-s4);
  border: 1px solid var(--sv-sep);
  background: var(--sv-card);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 10px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
}
.center {
  text-align: center;
  padding: var(--sv-s4) 0;
}
.muted {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
}
.item {
  overflow: hidden;
}
.hd {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 12px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--sv-label);
  font-family: inherit;
}
.t-txt {
  flex: 1;
  min-width: 0;
}
.t-txt b {
  font-size: var(--sv-fs-footnote);
  display: block;
}
.t-txt small {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption2);
  display: block;
  margin-top: 2px;
}
.acts {
  flex: none;
}
.pill {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  flex: none;
}
.pill.ok {
  background: color-mix(in srgb, #30d158 18%, transparent);
  color: #1d7a3a;
}
.pill.off {
  background: var(--sv-sep);
  color: var(--sv-label3);
}
[data-theme='dark'] .pill.ok {
  color: #6ee787;
}
.btn {
  border: 1px solid var(--sv-sep);
  background: transparent;
  color: var(--sv-indigo);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  font-family: inherit;
}
.btn.tiny {
  padding: 5px 10px;
  font-size: var(--sv-fs-caption2);
}
.btn.primary {
  background: var(--sv-indigo);
  border-color: var(--sv-indigo);
  color: #fff;
}
.btn:disabled {
  opacity: 0.5;
}
.json {
  background: var(--sv-bg);
  border: 1px solid var(--sv-sep);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: var(--sv-fs-caption2);
  line-height: 1.6;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0 14px 12px;
  max-height: 300px;
}
.detail .desc {
  font-size: var(--sv-fs-footnote);
  color: var(--sv-label2);
  line-height: 1.7;
  margin: 0 14px 8px;
}
.json.out {
  border-color: var(--sv-indigo);
}
.pv {
  margin-top: var(--sv-s5, 20px);
}
.pv h3 {
  font-size: var(--sv-fs-subhead);
  margin: 0 0 10px;
}
.in {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--sv-sep);
  background: var(--sv-bg);
  color: var(--sv-label);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: var(--sv-fs-footnote);
  font-family: inherit;
  margin-bottom: 8px;
}
.area {
  resize: vertical;
}
.pv-out {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pv-out .json {
  margin: 0;
}
</style>
