<script setup lang="ts">
import { computed } from 'vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import { useCrisisStore } from '@/stores/crisis'

/** 危机流内转介卡（DS3）：资源来自 crisis store 全站一份，危机场景禁用动效 */
const props = withDefaults(defineProps<{ level?: 'HIGH' | 'MEDIUM'; headline?: string }>(), {
  level: 'HIGH',
})

const crisis = useCrisisStore()
crisis.ensure()

const DEFAULT_HEADLINE: Record<string, string> = {
  HIGH: '现在这一刻也许很难，你的安全最重要——请连接下面的支持',
  MEDIUM: '这些资源也许能帮到你，把专业的事交给专业的人不吃亏',
}
const title = computed(() => props.headline || DEFAULT_HEADLINE[props.level])
const resources = computed(() => crisis.referral?.resources ?? [])
</script>

<template>
  <div class="referral" :class="level.toLowerCase()" role="note" :aria-label="'支持资源（' + (level === 'HIGH' ? '紧急' : '一般') + '）'">
    <b>{{ title }}</b>
    <ul v-if="resources.length">
      <li v-for="r in resources" :key="r.name">
        <span class="name">{{ r.name }}</span>
        <a v-if="r.type === 'PHONE'" class="value" :href="`tel:${r.value.replace(/[^0-9+]/g, '')}`">
          <SvIcon name="i-phone" :size="14" tone="inherit" />{{ r.value }}</a>
        <span v-else class="value">{{ r.value }}</span>
        <small v-if="r.note">{{ r.note }}</small>
      </li>
    </ul>
    <ul v-else>
      <li><span class="name">全国 24 小时心理援助热线</span><a class="value" href="tel:12356">12356</a></li>
    </ul>
    <p class="boundary">{{ crisis.referral?.boundary || '平台为自助工具，不做诊断。紧急情况请拨打 110/120。' }}</p>
    <router-link v-if="level === 'HIGH'" to="/crisis" class="more">查看全部支持资源 →</router-link>
  </div>
</template>

<style scoped>
.referral { border-radius: var(--sv-r-card); padding: 14px 16px; font-size: var(--sv-fs-subhead); line-height: 1.7; margin: 12px 0; }
.referral.high { background: #fdf2f1; border: 1px solid #f3c8c3; color: #8c3f36; }
.referral.medium { background: #fdf8ec; border: 1px solid #f0e0b8; color: #8a6414; }
[data-theme="dark"] .referral.high { background: color-mix(in srgb, var(--sv-red) 16%, var(--sv-card)); color: var(--sv-label); }
[data-theme="dark"] .referral.medium { background: color-mix(in srgb, var(--sv-amber) 14%, var(--sv-card)); color: var(--sv-label); }
.referral b { display: block; margin-bottom: 6px; }
ul { margin: 6px 0; padding-left: 18px; list-style: none; display: flex; flex-direction: column; gap: 8px; }
li { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.name { min-width: 9em; }
.value { color: #c04a3f; font-size: var(--sv-fs-callout); font-weight: 700; text-decoration: none; display: inline-flex; align-items: center; gap: 4px; }
.medium .value { color: #b07714; }
small { opacity: 0.75; font-size: var(--sv-fs-caption2); }
.boundary { margin: 8px 0 0; font-size: var(--sv-fs-caption2); opacity: 0.8; }
.more { display: inline-block; margin-top: 4px; font-size: var(--sv-fs-footnote); }
</style>
