<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { type ApiResp } from '../api/http'

interface Referral {
  boundary: string
  resources: { name: string; value: string; type: string; note: string }[]
}
const referral = ref<Referral | null>(null)

onMounted(async () => {
  if (referral.value) return
  try {
    const { data } = await http.get<ApiResp<Referral>>('/risk/resources')
    referral.value = data.data
  } catch { /* 转介卡降级为静态热线 */ }
})

const props = withDefaults(defineProps<{ level?: 'MEDIUM' | 'HIGH'; headline?: string }>(), {
  level: 'HIGH',
})

const DEFAULT_HEADLINE: Record<string, string> = {
  HIGH: '现在这一刻也许很难，你的安全最重要——请连接下面的支持',
  MEDIUM: '这些资源也许能帮到你，把专业的事交给专业的人不吃亏',
}
const title = () => props.headline || DEFAULT_HEADLINE[props.level]
</script>

<template>
  <div class="crisis" :class="level.toLowerCase()">
    <b>{{ title() }}</b>
    <ul v-if="referral">
      <li v-for="r in referral.resources" :key="r.name">
        <span class="name">{{ r.name }}</span>
        <b class="value">{{ r.value }}</b>
        <small>{{ r.note }}</small>
      </li>
    </ul>
    <ul v-else>
      <li><span class="name">全国 24 小时心理援助热线</span><b class="value">12356</b></li>
    </ul>
    <p class="boundary">{{ referral?.boundary || '平台为自助辅助工具，不做诊断；转介即服务终点。紧急情况请拨打 110/120。' }}</p>
    <router-link v-if="level === 'HIGH'" to="/crisis" class="more">查看全部支持资源 →</router-link>
  </div>
</template>

<style scoped>
.crisis { border-radius: 12px; padding: 14px 16px; font-size: 14px; line-height: 1.7; margin: 12px 0; }
.crisis.high { background: #fdf2f1; border: 1px solid #f3c8c3; color: #8c3f36; }
.crisis.medium { background: #fdf8ec; border: 1px solid #f0e0b8; color: #8a6414; }
.crisis b { display: block; margin-bottom: 6px; }
ul { margin: 6px 0; padding-left: 18px; }
li { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.name { min-width: 12em; }
.value { color: #c04a3f; font-size: 15px; }
.medium .value { color: #b07714; }
small { opacity: .75; }
.boundary { margin: 8px 0 0; font-size: 12px; opacity: .8; }
.more { display: inline-block; margin-top: 4px; font-size: 13px; }
</style>
