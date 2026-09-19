<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvSwipeActions from '@/components/ui/SvSwipeActions.vue'
import http, { type ApiResp } from '@/api/http'
import { confirmDialog, toast } from '@/stores/ui'

interface DiaryItem { id: number; recordDate: string; moodSelfRating: number; taskId: number; preview: string }

const router = useRouter()
const items = ref<DiaryItem[]>([])
const total = ref(0)
const page = ref(0)
const size = 20
const q = ref('')
const loading = ref(false)

const MOOD_FACE: Record<number, string> = { 1: '😖', 2: '😞', 3: '😐', 4: '🙂', 5: '😄' }

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
  try {
    if (reset) { page.value = 0; items.value = []; total.value = 0 }
    const { data } = await http.get<ApiResp<{ items: DiaryItem[]; total: number }>>('/diaries', {
      params: { q: q.value || undefined, page: page.value, size },
    })
    items.value = [...items.value, ...data.data.items]
    total.value = data.data.total
  } catch (e: any) {
    toast(e.message || '加载失败')
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

async function remove(it: DiaryItem) {
  const ok = await confirmDialog({
    title: '删除这篇日记？', danger: true, confirmText: '删除',
    message: '删除后不再出现在列表中，相关复盘报告会标记过期。',
  })
  if (!ok) return
  try {
    await http.delete(`/diaries/${it.id}`)
    toast('已删除')
    await load(true)
  } catch (e: any) {
    toast(e.message || '删除失败')
  }
}

onMounted(() => load(true))
</script>

<template>
  <div class="book">
    <SvNavBar title="日记本">
      <template #actions>
        <router-link to="/diaries/write" class="pen" aria-label="写一篇"><SvIcon name="i-pen" :size="20" /></router-link>
      </template>
    </SvNavBar>

    <div class="body">
      <label class="search">
        <SvIcon name="i-search" :size="16" tone="label2" />
        <input v-model="q" placeholder="搜索日记内容…" aria-label="搜索日记内容" @input="onSearch" />
      </label>

      <p v-if="!items.length && !loading" class="empty sv-muted">
        还没有日记。点右上角 ✎ 写一篇，心屿会帮你加密保存。
      </p>

      <section v-for="[month, list] in months" :key="month" class="month">
        <h3>{{ month.slice(0, 4) }} 年 {{ Number(month.slice(5, 7)) }} 月</h3>
        <SvSwipeActions v-for="it in list" :key="it.id" class="entry-wrap" @confirm="remove(it)">
          <article class="entry sv-surface" @click="router.push(`/diaries/${it.id}`)">
            <div class="entry-head">
              <time class="date">{{ it.recordDate.slice(5).replace('-', '/') }}</time>
              <span v-if="it.moodSelfRating" class="mood" :aria-label="`当日心情 ${it.moodSelfRating} 分`">{{ MOOD_FACE[it.moodSelfRating] }}</span>
            </div>
            <p class="preview">{{ it.preview }}</p>
            <span class="stripe" :style="{ background: 'var(--sv-indigo-soft)' }" aria-hidden="true" />
          </article>
        </SvSwipeActions>
      </section>

      <button v-if="items.length < total" class="sv-btn ghost sm" :disabled="loading" @click="more">
        加载更多（{{ items.length }}/{{ total }}）</button>
      <p v-if="loading" class="sv-cap loading" role="status">加载中…</p>

      <p class="sv-cap foot">日记原文与报告均以信封加密存储，仅你本人可见。</p>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.pen { display: grid; place-items: center; width: 44px; height: 44px; color: var(--sv-indigo); }
.search {
  display: flex; align-items: center; gap: 8px; margin-bottom: var(--sv-s3);
  background: var(--sv-card); border-radius: var(--sv-r-ctl); padding: 0 12px;
}
.search input { flex: 1; border: none; background: transparent; outline: none; padding: 12px 0;
  font-size: var(--sv-fs-subhead); color: var(--sv-label); font-family: inherit; }
.month { margin-bottom: var(--sv-s2); }
.month h3 { color: var(--sv-label2); font-size: var(--sv-fs-caption1); font-weight: 600; margin: var(--sv-s4) 6px var(--sv-s2); }
.entry-wrap { border-radius: var(--sv-r-card); overflow: hidden; margin-bottom: 10px; }
.entry { position: relative; padding: 14px 16px; cursor: pointer; overflow: hidden; }
.entry-head { display: flex; justify-content: space-between; align-items: center; }
.date { color: var(--sv-label2); font-size: var(--sv-fs-footnote); font-variant-numeric: tabular-nums; }
.mood { font-size: 18px; }
.preview { margin: 6px 0 0; color: var(--sv-label); font-size: var(--sv-fs-subhead); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.stripe { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; }
.empty { text-align: center; margin-top: 48px; }
.loading, .foot { text-align: center; padding: var(--sv-s2) 0; }
.foot { margin-top: var(--sv-s3); }
</style>
