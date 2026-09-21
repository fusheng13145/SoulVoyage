<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { diaryList, type DiaryRow } from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go } from '@/composables/session'
import { toast } from '@/utils/feedback'

/** 日记本（下篇·C1）：只回预览不回原文，正文要到详情页按需解密读取。 */
const { themeClass } = useTheme()
const rows = ref<DiaryRow[]>([])
const total = ref(0)
const keyword = ref('')
const loading = ref(false)
const loaded = ref(false)

/** 后端说"月份分组在前端做"：这里就是那"前端"，所以按 recordDate 的 YYYY-MM 归堆 */
const groups = computed(() => {
  const out: { month: string; items: DiaryRow[] }[] = []
  for (const r of rows.value) {
    const m = r.recordDate.slice(0, 7)
    const last = out[out.length - 1]
    if (last && last.month === m) last.items.push(r)
    else out.push({ month: m, items: [r] })
  }
  return out
})

onShow(() => {
  applyTheme()
  if (ensureSession()) void load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const r = await diaryList({ q: keyword.value.trim() || undefined, page: 0, size: 50 })
    rows.value = r.items
    total.value = r.total
  } catch (e) {
    toast((e as Error).message)
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function write() {
  go('/pages/diary/write')
}

function open(id: number) {
  go(`/pages/diary/detail?id=${id}`)
}
</script>

<template>
  <view class="sv-page diary" :class="themeClass">
    <text class="sv-h1">日记本</text>
    <text class="sv-muted">写下来的都会被好好收着：正文与笔记都以只有你解得开的方式存放。</text>

    <view class="search">
      <input
        v-model="keyword"
        class="sv-field"
        placeholder="在这本页里找一个词"
        placeholder-class="ph"
        confirm-type="search"
        @confirm="load"
      />
      <button class="sv-btn ghost search-btn" @tap="load">找</button>
    </view>

    <button class="sv-btn write" @tap="write">写一篇</button>

    <view v-if="loaded && !rows.length" class="sv-surface empty">
      <text class="sv-h2">{{ keyword ? '没有命中的页' : '还没有写过日记' }}</text>
      <text class="sv-muted">
        {{
          keyword ? '换个词试试，或者回去看看今天的打卡。' : '哪怕只写三行，也会被梳理成一份能回看的变化。'
        }}
      </text>
    </view>

    <view v-for="g in groups" :key="g.month" class="group">
      <text class="sv-cap month">{{ g.month.replace('-', ' 年 ') }} 月</text>
      <view v-for="r in g.items" :key="r.id" class="sv-surface item" @tap="open(r.id)">
        <view class="item-head">
          <text class="item-date">{{ r.recordDate.slice(8) }} 日</text>
          <view class="tags">
            <text v-if="r.source === 'VOICE'" class="tag voice">语音</text>
            <text v-if="r.moodSelfRating" class="tag">自评 {{ r.moodSelfRating }}/5</text>
          </view>
        </view>
        <text class="preview">{{ r.preview }}</text>
      </view>
    </view>

    <text v-if="total > rows.length" class="sv-cap more"
      >本页显示前 {{ rows.length }} 条，共 {{ total }} 条</text
    >
    <view class="tab-spacer" />
  </view>
</template>

<style scoped>
.diary {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.sv-h1 {
  margin-bottom: var(--sv-s1);
}
.search {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.search .sv-field {
  flex: 1;
}
.search-btn {
  width: 68px;
  padding: 0;
}
.ph {
  color: var(--sv-label3);
}
.write {
  width: 100%;
}
.group {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.month {
  padding-left: var(--sv-s1);
}
.item {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.item-head {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.item-date {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
}
.tags {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s1);
}
.tag {
  padding: 1px 8px;
  border-radius: var(--sv-r-pill);
  background: var(--sv-fill2);
  color: var(--sv-label2);
  font-size: var(--sv-fs-caption2);
}
.tag.voice {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
}
.preview {
  font-size: var(--sv-fs-subhead);
  line-height: 1.6;
  color: var(--sv-label2);
}
.empty {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.more {
  text-align: center;
}
.tab-spacer {
  height: var(--sv-s4);
}
</style>
