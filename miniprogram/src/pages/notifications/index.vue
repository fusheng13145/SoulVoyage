<script setup lang="ts">
import { ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { notifications, readAllNotices, readNotice, type Notice } from '@/api/app'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go, goTab } from '@/composables/session'
import { toast } from '@/utils/feedback'

/** 通知：小程序里它是"提醒的收件箱"，链接只认端内已有的路由，
 *  指向网页端专属页面（练习室、成长档案）的先标已读再说明去哪看，不做假跳转。 */
const { themeClass } = useTheme()
const rows = ref<Notice[]>([])
const total = ref(0)
const loading = ref(false)
const loaded = ref(false)

const TAB_LINKS = ['/pages/today/index', '/pages/diary/index', '/pages/companion/index', '/pages/me/index']

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
    const r = await notifications(0, 50)
    rows.value = r.items
    total.value = r.total
  } catch (e) {
    toast((e as Error).message)
  } finally {
    loading.value = false
    loaded.value = true
  }
}

async function open(n: Notice) {
  if (!n.readAt) {
    try {
      await readNotice(n.id)
      n.readAt = new Date().toISOString()
    } catch {
      /* 标已读失败不挡跳转：内容已经看到了，红点晚一点消失没关系 */
    }
  }
  const link = n.link || ''
  if (!link) return
  if (link.startsWith('/pages/')) {
    if (TAB_LINKS.includes(link)) goTab(link)
    else go(link)
    return
  }
  toast('这条详细内容在网页端：这里先给你看到标题和摘要')
}

async function markAll() {
  try {
    await readAllNotices()
    await load()
    toast('都标成看过了')
  } catch (e) {
    toast((e as Error).message)
  }
}
</script>

<template>
  <view class="sv-page notices" :class="themeClass">
    <view class="head">
      <view class="head-text">
        <text class="sv-h1">通知</text>
        <text class="sv-muted">共 {{ total }} 条</text>
      </view>
      <text class="link" @tap="markAll">全部已读</text>
    </view>

    <view v-if="loaded && !rows.length" class="sv-surface">
      <text class="sv-muted">还没有消息。这里安静一点通常是好事。</text>
    </view>

    <view v-for="n in rows" :key="n.id" class="sv-surface item" @tap="open(n)">
      <view class="item-head">
        <text class="title" :class="{ unread: !n.readAt }">{{ n.title }}</text>
        <text v-if="!n.readAt" class="dot" />
      </view>
      <text class="body">{{ n.body }}</text>
      <text class="sv-cap">{{ n.createdAt.replace('T', ' ').slice(0, 16) }}</text>
    </view>
  </view>
</template>

<style scoped>
.notices {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.head {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
}
.head-text {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.sv-h1 {
  margin-bottom: 0;
}
.link {
  color: var(--sv-indigo);
  font-size: var(--sv-fs-footnote);
}
.item {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
}
.item-head {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: var(--sv-s2);
}
.title {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
}
.title.unread {
  color: var(--sv-indigo);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sv-red);
}
.body {
  font-size: var(--sv-fs-footnote);
  line-height: 1.6;
  color: var(--sv-label2);
}
</style>
