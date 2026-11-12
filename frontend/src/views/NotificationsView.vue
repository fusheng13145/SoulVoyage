<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import { notificationApi } from '@/api/domains/notification'
import { toast } from '@/stores/ui'

interface Notice {
  id: number
  kind: string
  title: string
  body: string
  link: string | null
  readAt: string | null
  createdAt: string
}

const KIND_LABEL: Record<string, string> = {
  SYSTEM: '心屿提醒',
  PLAN: '计划',
  PLAN_SUMMARY: '计划小结',
  LETTER: '成长来信',
  ACHIEVEMENT: '成就',
  REPORT: '报告',
  CRISIS: '关怀',
}

const router = useRouter()
const items = ref<Notice[]>([])
const unreadCount = ref(0)
const loading = ref(true)

const groups = computed(() => {
  const unread = items.value.filter(n => !n.readAt)
  const read = items.value.filter(n => n.readAt)
  return [
    { label: '未读', list: unread },
    { label: '往日期待', list: read },
  ].filter(g => g.list.length)
})

async function load() {
  loading.value = true
  try {
    const data = await notificationApi.list<{ items: Notice[]; total: number; unreadCount: number }>({
      page: 0,
      size: 50,
    })
    items.value = data.items
    unreadCount.value = data.unreadCount
  } catch (e) {
    toast((e as Error).message || '通知没拉起来')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function open(n: Notice) {
  if (!n.readAt) {
    n.readAt = new Date().toISOString()
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    notificationApi.read(n.id).catch(() => {
      /* 置读失败下次再补 */
    })
  }
  if (n.link) router.push(n.link)
}

async function readAll() {
  items.value.forEach(n => {
    n.readAt = n.readAt || new Date().toISOString()
  })
  unreadCount.value = 0
  await notificationApi.readAll().catch(() => toast('操作失败'))
}

const fmt = (s: string) => (s ? s.slice(0, 16).replace('T', ' ') : '')
</script>

<template>
  <div class="notice-page">
    <SvNavBar
      title="通知中心"
      :subtitle="unreadCount ? `${unreadCount} 条未读` : '都是好消息'"
      back="今日"
      back-to="/today"
      :large="false"
    >
      <template #actions>
        <button v-if="unreadCount" class="read-all" @click="readAll">全部已读</button>
      </template>
    </SvNavBar>

    <SvPageBody>
      <p v-if="loading" class="sv-muted sv-center tight">加载中…</p>
      <SvCard v-else-if="!items.length" class="empty">
        <p class="sv-muted sv-center tight">
          这里很安静——提醒、计划、来信到时会轻轻出现在这里。<br />你不需要随时回来，它们会等你。
        </p>
      </SvCard>

      <template v-for="g in groups" :key="g.label">
        <h3 class="grp">{{ g.label }}</h3>
        <SvCard :pad="false">
          <button v-for="n in g.list" :key="n.id" class="row" :class="{ unread: !n.readAt }" @click="open(n)">
            <span class="r-ico"><SvIcon name="i-bell" :size="18" tone="inherit" /></span>
            <span class="r-txt">
              <b>{{ n.title }}</b>
              <small>{{ n.body }}</small>
              <em class="meta">{{ KIND_LABEL[n.kind] || n.kind }} · {{ fmt(n.createdAt) }}</em>
            </span>
            <SvIcon v-if="n.link" name="i-arrow" :size="16" tone="label2" />
          </button>
        </SvCard>
      </template>
    </SvPageBody>
  </div>
</template>

<style scoped>
.tight {
  line-height: 1.7;
}
.read-all {
  border: none;
  background: transparent;
  color: var(--sv-accent);
  font-size: var(--sv-fs-footnote);
  cursor: pointer;
  padding: 10px 14px;
  font-family: inherit;
}
.grp {
  font-size: var(--sv-fs-caption1);
  color: var(--sv-label3);
  margin: var(--sv-s4) 4px var(--sv-s2);
}
.row {
  display: flex;
  gap: var(--sv-s3);
  align-items: center;
  width: 100%;
  min-height: 60px;
  padding: 12px 16px;
  border: none;
  border-bottom: 1px solid var(--sv-sep);
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--sv-label);
  font-family: inherit;
}
.row:last-child {
  border-bottom: none;
}
.r-ico {
  position: relative;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: none;
  color: var(--sv-label3);
}
.unread .r-ico {
  color: var(--sv-accent);
}
.unread .r-ico::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--sv-accent);
}
.r-txt {
  flex: 1;
  min-width: 0;
}
.r-txt b {
  font-size: var(--sv-fs-body);
  font-weight: 500;
  display: block;
}
.r-txt small {
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
  display: block;
  margin-top: 3px;
  line-height: var(--sv-lh-body);
}
.meta {
  font-style: normal;
  color: var(--sv-label3);
  font-size: var(--sv-fs-caption1);
  display: block;
  margin-top: 5px;
}
.empty {
  margin-top: var(--sv-s4);
}
</style>
