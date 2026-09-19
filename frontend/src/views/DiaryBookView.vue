<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import http, { type ApiResp } from '../api/http'

interface DiaryItem { id: number; recordDate: string; moodSelfRating: number; taskId: number; preview: string }
interface ReportMeta { id: number; type: string; title: string; riskLevel: string; stale: number }
interface EmotionPoint { sourceType: string; emotion: string; valence: number; intensity: number; eventTags: { tag: string; evidence?: string }[] }
interface DiaryDetail {
  id: number; recordDate: string; moodSelfRating: number; content: string
  taskId: number; reports: ReportMeta[]; emotions: EmotionPoint[]
}

const items = ref<DiaryItem[]>([])
const total = ref(0)
const page = ref(0)
const size = 20
const q = ref('')
const loading = ref(false)
const listError = ref('')

const detail = ref<DiaryDetail | null>(null)
const detailLoading = ref(false)
const editing = ref(false)
const editText = ref('')
const busy = ref('')          // '' | 'reanalyzing'
const busyTip = ref('')
let pollTimer: number | undefined

const MOOD_EMOJI: Record<number, string> = { 1: '😖', 2: '😞', 3: '😐', 4: '🙂', 5: '😄' }
const REPORT_TITLES: Record<string, string> = {
  DIARY_TRACE: '溯源复盘', DIARY_REPORT: '复盘报告', SIMULATE_REVIEW: '模拟复盘', WEEKLY: '周报',
}

/** 按自然月分组的时间线 */
const months = computed(() => {
  const map = new Map<string, DiaryItem[]>()
  for (const it of items.value) {
    const key = it.recordDate.slice(0, 7)
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(it)
  }
  return [...map.entries()]
})

async function load(reset = true) {
  if (loading.value) return
  loading.value = true
  listError.value = ''
  try {
    if (reset) { page.value = 0; items.value = []; total.value = 0 }
    const { data } = await http.get<ApiResp<{ items: DiaryItem[]; total: number }>>('/diaries', {
      params: { q: q.value || undefined, page: page.value, size },
    })
    items.value = [...items.value, ...data.data.items]
    total.value = data.data.total
  } catch (e: any) {
    listError.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function more() {
  page.value += 1
  load(false)
}

let searchTimer: number | undefined
function onSearch() {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => load(true), 350)
}

async function open(it: DiaryItem) {
  if (detailLoading.value || busy.value) return
  detailLoading.value = true
  editing.value = false
  try {
    const { data } = await http.get<ApiResp<DiaryDetail>>(`/diaries/${it.id}`)
    detail.value = data.data
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e: any) {
    listError.value = e.message || '打开失败'
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  if (busy.value) return
  stopPoll()
  detail.value = null
}

async function saveEdit() {
  const d = detail.value
  if (!d || !editText.value.trim()) return
  busy.value = 'saving'
  try {
    await http.put(`/diaries/${d.id}`, { content: editText.value, moodSelfRating: d.moodSelfRating })
    const { data } = await http.get<ApiResp<DiaryDetail>>(`/diaries/${d.id}`)
    detail.value = data.data
    editing.value = false
    load(true)
  } catch (e: any) {
    listError.value = e.message || '保存失败'
  } finally {
    busy.value = ''
  }
}

async function remove() {
  const d = detail.value
  if (!d || !window.confirm('删除后不再出现在列表中，相关复盘报告会标记过期。确定删除？')) return
  try {
    await http.delete(`/diaries/${d.id}`)
    detail.value = null
    await load(true)
  } catch (e: any) {
    listError.value = e.message || '删除失败'
  }
}

/** 重新分析：按当前（可能已编辑的）内容再跑一轮 DIARY_PIPELINE，轮询到结束 */
async function reanalyze() {
  const d = detail.value
  if (!d || busy.value) return
  busy.value = 'reanalyzing'
  busyTip.value = '心屿正在重新梳理…'
  listError.value = ''
  try {
    const { data } = await http.post<ApiResp<{ taskNo: string }>>(`/diaries/${d.id}/reanalyze`)
    pollTask(data.data.taskNo)
  } catch (e: any) {
    listError.value = e.message || '提交重新分析失败'
    busy.value = ''
  }
}

function pollTask(taskNo: string) {
  const tick = async () => {
    try {
      const { data } = await http.get<ApiResp<{ status: string }>>(`/tasks/${taskNo}`)
      const st = data.data.status
      if (st === 'SUCCESS' || st === 'PARTIAL_SUCCESS') {
        stopPoll()
        busy.value = ''
        const id = detail.value?.id
        await load(true)
        const it = items.value.find(x => x.id === id)
        if (it) await open(it)
        return
      }
      if (st === 'FAILED' || st === 'CANCELED') {
        stopPoll()
        busy.value = ''
        listError.value = '重新分析未完成，稍后再试一次'
        return
      }
      pollTimer = window.setTimeout(tick, 2000)
    } catch {
      pollTimer = window.setTimeout(tick, 4000)   // 瞬时网络抖动不打断轮询
    }
  }
  pollTimer = window.setTimeout(tick, 2000)
}

function stopPoll() {
  if (pollTimer !== undefined) { window.clearTimeout(pollTimer); pollTimer = undefined }
}

onMounted(() => load(true))
onUnmounted(stopPoll)
</script>

<template>
  <div class="book">
    <header>
      <router-link to="/" class="back">← 首页</router-link>
      <b>📖 日记本</b>
      <router-link to="/diary" class="write-link">写一篇 →</router-link>
    </header>
    <main>
      <input v-model="q" class="search" placeholder="搜索日记内容…" @input="onSearch" />
      <p v-if="listError" class="err">{{ listError }}</p>

      <!-- 详情视图 -->
      <div v-if="detail" class="detail">
        <div class="detail-head">
          <span class="date">{{ detail.recordDate }} {{ MOOD_EMOJI[detail.moodSelfRating] || '' }}</span>
          <span class="spacer" />
          <button class="ghost" :disabled="!!busy" @click="editing = !editing; editText = detail.content">
            {{ editing ? '取消' : '编辑' }}</button>
          <button v-if="!editing" class="ghost" :disabled="!!busy" @click="reanalyze">
            {{ busy === 'reanalyzing' ? '分析中…' : '重新分析' }}</button>
          <button class="ghost danger" :disabled="!!busy" @click="remove">删除</button>
          <button class="ghost" :disabled="!!busy" @click="closeDetail">返回</button>
        </div>
        <p v-if="busyTip" class="busy">{{ busyTip }}</p>

        <textarea v-if="editing" v-model="editText" rows="8" maxlength="5000"></textarea>
        <div v-if="editing" class="edit-actions">
          <span class="hint">保存后旧报告会标记为「待更新」，可一键重新分析</span>
          <button class="primary small" :disabled="!editText.trim() || !!busy" @click="saveEdit">保存</button>
        </div>
        <p v-if="!editing" class="content">{{ detail.content }}</p>

        <div v-if="detail.emotions.length" class="section">
          <h4>当日情绪</h4>
          <ul class="points">
            <li v-for="(p, i) in detail.emotions" :key="i">
              <span class="src">{{ p.sourceType === 'SIMULATION' ? '🎭 模拟' : '📔 日记' }}</span>
              <b>{{ p.emotion }}</b>
              <span class="meta">强度 {{ Math.round(p.intensity * 100) }}% · 效价 {{ p.valence }}</span>
              <em v-for="t in p.eventTags" :key="t.tag">{{ t.tag }}</em>
            </li>
          </ul>
        </div>

        <div v-if="detail.reports.length" class="section">
          <h4>关联复盘报告</h4>
          <ul class="reports">
            <li v-for="r in detail.reports" :key="r.id">
              <b>{{ REPORT_TITLES[r.type] || r.title || r.type }}</b>
              <span class="risk" :class="r.riskLevel.toLowerCase()">{{ r.riskLevel }}</span>
              <span v-if="r.stale" class="stale">内容已更新，结论待重跑</span>
              <router-link v-else to="/archive" class="to-archive">去档案看 →</router-link>
            </li>
          </ul>
        </div>
        <p v-if="!detail.emotions.length && !detail.reports.length && !editing" class="hint">
          这篇还没有分析结果，点「重新分析」让心屿跑一次。</p>
      </div>

      <!-- 列表视图：月份分组时间线 -->
      <template v-else>
        <p v-if="!items.length && !loading" class="hint empty">还没有日记。去「情绪日记」写第一篇，心屿会帮你加密保存。</p>
        <section v-for="[month, list] in months" :key="month" class="month">
          <h3>{{ month.slice(0, 4) }} 年 {{ month.slice(5, 7) }} 月</h3>
          <article v-for="it in list" :key="it.id" class="entry" @click="open(it)">
            <div class="entry-head">
              <span class="date">{{ it.recordDate }}</span>
              <span class="mood">{{ MOOD_EMOJI[it.moodSelfRating] || '' }}</span>
            </div>
            <p class="preview">{{ it.preview }}</p>
          </article>
        </section>
        <button v-if="items.length < total" class="ghost more" :disabled="loading" @click="more">
          加载更多（{{ items.length }}/{{ total }}）</button>
        <p v-if="loading" class="hint">加载中…</p>
      </template>

      <p class="disclaimer">日记原文与报告均以信封加密存储，仅你本人可见；删除为软删除，导出数据时同样只你可见。</p>
    </main>
  </div>
</template>

<style scoped>
.book { min-height: 100vh; background: #f5f7fd; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
.write-link { margin-left: auto; color: #5b6cff; text-decoration: none; font-size: 14px; }
main { max-width: 720px; margin: 24px auto; padding: 0 16px; }
.search { width: 100%; border: 1px solid #dde3f3; border-radius: 10px; padding: 10px 14px; font-size: 14px; margin-bottom: 14px; }
.err { color: #d4574e; font-size: 14px; }
.month h3 { color: #8a93b5; font-size: 13px; font-weight: 600; margin: 18px 0 8px; }
.entry { background: #fff; border-radius: 12px; padding: 14px 16px; margin-bottom: 10px; box-shadow: 0 2px 10px rgba(80,90,160,.08); cursor: pointer; }
.entry:hover { transform: translateY(-1px); }
.entry-head { display: flex; justify-content: space-between; align-items: center; }
.date { color: #5c6483; font-size: 13px; }
.mood { font-size: 18px; }
.preview { margin: 6px 0 0; color: #40466b; font-size: 14px; line-height: 1.6; }
.more { width: 100%; margin-top: 6px; }
.hint { color: #8a93b5; font-size: 13px; }
.empty { text-align: center; margin-top: 40px; }
.detail { background: #fff; border-radius: 12px; padding: 18px 20px; box-shadow: 0 2px 10px rgba(80,90,160,.08); }
.detail-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.spacer { flex: 1; }
.ghost { border: 1px solid #dde3f3; background: #fff; border-radius: 8px; padding: 5px 12px; font-size: 13px; cursor: pointer; color: #40466b; }
.ghost:disabled { opacity: .5; cursor: not-allowed; }
.ghost.danger { color: #d4574e; border-color: #f2d4d1; }
.content { white-space: pre-wrap; color: #40466b; font-size: 15px; line-height: 1.8; }
.busy { color: #5b6cff; font-size: 13px; }
textarea { width: 100%; border: 1px solid #dde3f3; border-radius: 10px; padding: 12px; font-size: 14px; font-family: inherit; resize: vertical; }
.edit-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.primary { background: #5b6cff; color: #fff; border: 0; border-radius: 8px; padding: 8px 18px; cursor: pointer; }
.primary.small { padding: 6px 16px; font-size: 14px; }
.primary:disabled { opacity: .55; cursor: not-allowed; }
.section { margin-top: 16px; border-top: 1px dashed #e8ecf8; padding-top: 12px; }
.section h4 { margin: 0 0 8px; color: #40466b; font-size: 14px; }
.points, .reports { margin: 0; padding-left: 18px; font-size: 13px; color: #40466b; line-height: 1.9; }
.points .src { color: #8a93b5; margin-right: 6px; }
.points .meta { color: #8a93b5; margin-left: 8px; }
.points em { margin-left: 8px; font-style: normal; background: #eef0ff; border-radius: 8px; padding: 1px 6px; }
.reports .risk { margin-left: 8px; font-size: 11px; border-radius: 8px; padding: 1px 6px; background: #eef1fa; color: #5c6483; }
.reports .risk.high { background: #fbe4e2; color: #b4423a; }
.reports .risk.medium { background: #fdf1e0; color: #a8722f; }
.stale { margin-left: 8px; color: #a8722f; font-size: 12px; }
.to-archive { margin-left: 8px; color: #5b6cff; text-decoration: none; font-size: 12px; }
.disclaimer { margin-top: 18px; font-size: 12px; color: #9aa1bd; text-align: center; }
</style>
