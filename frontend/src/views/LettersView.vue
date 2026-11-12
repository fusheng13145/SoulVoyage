<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvPageBody from '@/components/ui/SvPageBody.vue'
import SvDisclaimer from '@/components/ui/SvDisclaimer.vue'
import { letterApi } from '@/api/domains/letter'
import { toast } from '@/stores/ui'

interface Letter {
  id: string
  statWeek: string
  createdAt: string
  letter: string
  weekGlow: string
}

const items = ref<Letter[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const data = await letterApi.list<{ items: Letter[]; total: number }>()
    items.value = data.items
  } catch (e) {
    toast((e as Error).message || '来信没取到')
  } finally {
    loading.value = false
  }
})

const weekCn = (w: string) => {
  const m = w.match(/^(\d{4})-W(\d{2})$/)
  return m ? `${m[1]} 年第 ${Number(m[2])} 周` : w
}
</script>

<template>
  <div class="letters">
    <SvNavBar
      title="成长来信"
      subtitle="每周一 07:00，写给正在长大的你"
      back="今日"
      back-to="/today"
      :large="false"
    />

    <SvPageBody>
      <p v-if="loading" class="sv-muted sv-center">展开信封中…</p>
      <template v-else-if="items.length">
        <SvCard v-for="l in items" :key="l.id" class="mail">
          <div class="m-head">
            <div>
              <b>{{ weekCn(l.statWeek) }}</b>
              <small v-if="l.weekGlow" class="glow">{{ l.weekGlow }}</small>
            </div>
          </div>
          <p class="m-body">{{ l.letter }}</p>
          <p class="sv-cap m-foot">
            写于 {{ l.createdAt.slice(0, 16).replace('T', ' ') }} · 信中的「」都是你自己说过的原话
          </p>
        </SvCard>
      </template>
      <SvCard v-else class="empty">
        <p class="sv-muted sv-center">
          还没有来信。第一封会在下周一早上抵达——只要上周你有过打卡或日记，心屿就会写。
        </p>
      </SvCard>
      <SvDisclaimer
        text="来信由 AI 基于你上周的记录写成，引用只来自你自己的原话与统计数字；它记得的，都是你愿意让它记得的。"
      />
    </SvPageBody>
  </div>
</template>

<style scoped>
.mail {
  margin-bottom: var(--sv-s3);
}
.m-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--sv-s3);
}
.m-head b {
  display: block;
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-footnote);
  font-weight: 400;
  color: var(--sv-label3);
  letter-spacing: 0.4px;
}
.glow {
  display: block;
  color: var(--sv-ok);
  font-size: var(--sv-fs-caption1);
  margin-top: 4px;
}
.m-body {
  white-space: pre-wrap;
  font-family: var(--sv-serif);
  font-size: var(--sv-fs-prose);
  line-height: var(--sv-lh-prose);
  max-width: 68ch;
}
.m-foot {
  margin-top: var(--sv-s3);
}
.empty {
  margin-bottom: var(--sv-s3);
}
</style>
