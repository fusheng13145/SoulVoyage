<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SvIcon from '@/components/ui/SvIcon.vue'
import { useCrisisStore, type RefResource } from '@/stores/crisis'
import { useAuthStore } from '@/stores/auth'

const crisis = useCrisisStore()
const auth = useAuthStore()
const resources = ref<RefResource[]>([])
const loading = ref(true)

onMounted(async () => {
  await crisis.ensure()
  resources.value = await crisis.resources()
  loading.value = false
  if (auth.me) crisis.refreshProfile().catch(() => { /* 匿名可浏览 */ })
})

const tel = (v: string) => `tel:${v.replace(/[^0-9+]/g, '')}`
</script>

<template>
  <div class="crisis-page">
    <header>
      <router-link to="/today" class="back" aria-label="返回首页"><SvIcon name="i-back" :size="18" tone="inherit" /> 返回</router-link>
      <span class="ct">支持资源</span>
    </header>

    <main>
      <p class="intro">如果此刻很难熬，下面这些是真实、免费、可以随时连接的人和渠道。
        联系他们不代表「严重了」，只代表你愿意照顾自己。</p>

      <p v-if="loading" class="sv-muted" role="status">资源加载中…</p>
      <template v-else>
        <a v-for="r in resources" :key="r.name" class="row sv-surface" :href="r.type === 'PHONE' ? tel(r.value) : r.value"
          :aria-label="`${r.type === 'PHONE' ? '拨打' : '联系'} ${r.name} ${r.value}`">
          <span class="r-ico" aria-hidden="true"><SvIcon name="i-phone" :size="22" tone="inherit" /></span>
          <span class="r-mid">
            <span class="rname">{{ r.name }}</span>
            <span class="rnote sv-cap">{{ r.note }}</span>
          </span>
          <b class="rvalue">{{ r.value }}</b>
        </a>

        <div class="emergency">
          紧急情况（正在发生伤害）请直接拨打
          <span class="nums"><a href="tel:110">110</a> / <a href="tel:120">120</a></span>
        </div>
        <p class="sv-cap boundary">{{ crisis.referral?.boundary || '心屿漫行是自助工具，不做诊断，也不能替代专业帮助。转介即我们能陪你走的最远一步。' }}</p>
      </template>

      <p class="sv-cap warm">你不需要独自扛着这一切。</p>
    </main>
  </div>
</template>

<style scoped>
.crisis-page { min-height: 100%; background: var(--sv-bg); }
header {
  position: sticky; top: 0; z-index: 20;
  display: flex; align-items: center; gap: var(--sv-s3);
  padding: calc(var(--sv-safe-t) + 8px) var(--sv-s4) 8px;
  background: color-mix(in srgb, var(--sv-bg) 78%, transparent);
  backdrop-filter: var(--sv-blur); -webkit-backdrop-filter: var(--sv-blur);
  border-bottom: 1px solid var(--sv-sep);
}
.back { display: inline-flex; align-items: center; gap: 2px; color: var(--sv-indigo); min-height: 44px; font-size: var(--sv-fs-subhead); }
.ct { margin: 0 auto; font-weight: 600; font-size: var(--sv-fs-headline); }
main { max-width: 640px; margin: 0 auto; padding: var(--sv-s4) var(--sv-s4) var(--sv-s7); }
.intro { color: var(--sv-label); font-size: var(--sv-fs-subhead); line-height: 1.9; margin-bottom: var(--sv-s5); }
.row {
  display: flex; align-items: center; gap: var(--sv-s3); padding: var(--sv-s4);
  margin-bottom: 10px; color: var(--sv-label);
}
.r-ico { display: grid; place-items: center; width: 44px; height: 44px; flex: none; border-radius: 14px;
  background: color-mix(in srgb, var(--sv-red) 12%, transparent); color: var(--sv-red); }
.r-mid { flex: 1 1 0; min-width: 0; }
.rname { display: block; font-size: var(--sv-fs-subhead); }
.rnote { display: block; margin-top: 2px; }
.rvalue {
  flex: 0 1 auto; max-width: 52%; text-align: right; line-height: 1.35;
  font-size: var(--sv-fs-title2); font-weight: 700; color: var(--sv-red); font-variant-numeric: tabular-nums;
}
.emergency { text-align: center; margin: var(--sv-s5) 0 var(--sv-s3); color: var(--sv-label2); font-size: var(--sv-fs-footnote); }
.nums a { color: var(--sv-red); font-size: var(--sv-fs-title3); font-weight: 700; text-decoration: none; }
.boundary { border-top: 1px dashed var(--sv-sep); padding-top: var(--sv-s3); line-height: 1.7; }
.warm { text-align: center; margin-top: var(--sv-s6); font-size: var(--sv-fs-footnote); color: var(--sv-label2); }
</style>
