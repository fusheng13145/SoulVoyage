<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvSwipeActions from '@/components/ui/SvSwipeActions.vue'
import { diaryApi } from '@/api/domains/diary'
import { confirmDialog, toast } from '@/stores/ui'
import { displayMonthDay } from '@/utils/date'
interface DiaryItem {
  id: number
  recordDate: string
  moodSelfRating: number
  taskId: number
  source: string
  preview: string
}

const router = useRouter()
const items = ref<DiaryItem[]>([])
const total = ref(0)
const page = ref(0)
const size = 20
const q = ref('')
const loading = ref(false)

const MOOD_TONE: Record<number, string> = {
  1: 'var(--sv-danger)',
  2: 'var(--sv-warn)',
  3: 'var(--sv-label3)',
  4: 'color-mix(in srgb, var(--sv-ok) 60%, var(--sv-label3))',
  5: 'var(--sv-ok)',
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

async function load(reset = true, target = 0) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      items.value = []
      total.value = 0
    }
    const next = reset ? 0 : target
    const data = await diaryApi.list<{ items: DiaryItem[]; total: number }>({
      q: q.value || undefined,
      page: next,
      size,
    })
    items.value = reset ? data.items : [...items.value, ...data.items]
    total.value = data.total
    page.value = next
  } catch (e) {
    toast((e as Error).message || '加载失败')
  } finally {
    loading.value = false
  }
}

function more() {
  load(false, page.value + 1)
}

let searchTimer: number | undefined
function onSearch() {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => load(true), 350)
}

async function remove(it: DiaryItem) {
  const ok = await confirmDialog({
    title: '删除这篇日记？',
    danger: true,
    confirmText: '删除',
    message: '删除后不再出现在列表中，相关复盘报告会标记过期。',
  })
  if (!ok) return
  try {
    await diaryApi.remove(it.id)
    toast('已删除')
    await load(true)
  } catch (e) {
    toast((e as Error).message || '删除失败')
  }
}

onMounted(() => load(true))
</script>

<template>
  <div class="book">
    <SvNavBar title="日记本">
      <template #actions>
        <router-link to="/diaries/write" class="pen" aria-label="写一篇"
          ><SvIcon name="i-pen" :size="20"
        /></router-link>
      </template>
    </SvNavBar>

    <SvPageBody>
      <label class="search">
        <SvIcon name="i-search" :size="16" tone="label2" />
        <input v-model="q" placeholder="搜索日记内容…" aria-label="搜索日记内容" @input="onSearch" />
      </label>

      <p v-if="!items.length && !loading" class="empty sv-muted">
        还没有日记。点右上角写一篇，心屿会帮你加密保存。
      </p>

      <section v-for="[month, list] in months" :key="month" class="month">
        <h3>{{ month.slice(0, 4) }} 年 {{ Number(month.slice(5, 7)) }} 月</h3>
        <SvSwipeActions v-for="it in list" :key="it.id" class="entry-wrap" @confirm="remove(it)">
          <article class="entry" @click="router.push(`/diaries/${it.id}`)">
            <div class="entry-head">
              <time class="date">{{ displayMonthDay(it.recordDate) }}</time>
              <span class="flags">
                <span
                  v-if="it.moodSelfRating"
                  class="mood-dot"
                  :style="{ background: MOOD_TONE[it.moodSelfRating] }"
                  :aria-label="`当日心情 ${it.moodSelfRating} 分`"
                />
                <span v-if="it.source === 'VOICE'" class="src" title="语音转写" aria-label="语音转写的日记">
                  <SvIcon name="i-mic" :size="14" tone="label2" />
                </span>
              </span>
            </div>
            <p class="preview">{{ it.preview }}</p>
          </article>
        </SvSwipeActions>
      </section>

      <button v-if="items.length < total" class="sv-btn ghost sm" :disabled="loading" @click="more">
        加载更多（{{ items.length }}/{{ total }}）
      </button>
      <p v-if="loading" class="sv-cap loading" role="status">加载中…</p>

      <p class="sv-cap foot">日记原文与报告均以信封加密存储，仅你本人可见。</p>
    </SvPageBody>
  </div>
</template>

<style scoped>
.pen {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--sv-accent);
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  margin-bottom: var(--sv-s3);
  background: var(--sv-card2);
  border: 1px solid var(--sv-line-strong);
  border-radius: var(--sv-r-ctl);
  padding: 0 12px;
}
.search input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  padding: 8px 0;
  font-size: var(--sv-fs-body);
  color: var(--sv-label);
  font-family: inherit;
}
.search input::placeholder {
  color: var(--sv-label3);
}
.month {
  margin-bottom: var(--sv-s2);
}
.month h3 {
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
  font-weight: 400;
  margin: var(--sv-s5) 0 var(--sv-s1);
}
.entry-wrap {
  border-radius: 0;
  overflow: hidden;
}
.entry {
  position: relative;
  padding: 14px 0;
  border-bottom: 1px solid var(--sv-sep);
  cursor: pointer;
}
.entry-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.date {
  color: var(--sv-label3);
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-footnote);
  font-variant-numeric: tabular-nums;
}
.mood-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.flags {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.src {
  display: inline-flex;
  color: var(--sv-label3);
}
.preview {
  margin: 6px 0 0;
  color: var(--sv-label);
  font-size: var(--sv-fs-body);
  line-height: var(--sv-lh-body);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.empty {
  text-align: center;
  margin-top: 48px;
}
.loading,
.foot {
  text-align: center;
  padding: var(--sv-s2) 0;
}
.foot {
  margin-top: var(--sv-s3);
}
</style>
