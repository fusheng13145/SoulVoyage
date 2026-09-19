<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http, { type ApiResp } from '../api/http'

interface Referral {
  boundary: string
  resources: { name: string; value: string; type: string; note: string }[]
}
const referral = ref<Referral | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    const { data } = await http.get<ApiResp<Referral>>('/risk/resources')
    referral.value = data.data
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="crisis-page">
    <header>
      <router-link to="/" class="back">← 返回</router-link>
      <b>需要支持的时候，你并不孤单</b>
    </header>
    <main>
      <p class="intro">如果此刻很难熬，下面这些是真实、免费、可以随时连接的人和渠道。
        联系他们不代表"严重了"，只代表你愿意照顾自己。</p>

      <div v-if="loading" class="hint">加载中…</div>
      <template v-else-if="referral">
        <div v-for="r in referral.resources" :key="r.name" class="row">
          <div class="rname">{{ r.name }}</div>
          <div class="rvalue">{{ r.value }}</div>
          <div class="rnote">{{ r.note }}</div>
        </div>
        <div class="emergency">紧急情况（正在发生伤害）请直接拨打 <b>110 / 120</b></div>
        <p class="boundary">{{ referral.boundary }}</p>
      </template>
    </main>
  </div>
</template>

<style scoped>
.crisis-page { min-height: 100vh; background: #fffaf8; }
header { display: flex; align-items: center; gap: 16px; padding: 14px 28px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,.05); }
.back { color: #5b6cff; text-decoration: none; }
main { max-width: 640px; margin: 24px auto; padding: 0 16px; }
.intro { color: #6a5a56; font-size: 15px; line-height: 1.8; }
.row { background: #fff; border: 1px solid #f3e2dd; border-radius: 12px; padding: 14px 16px; margin-bottom: 10px; }
.rname { color: #8c3f36; font-size: 14px; }
.rvalue { font-size: 22px; font-weight: 700; color: #c04a3f; margin: 2px 0; }
.rnote { font-size: 12px; color: #9a8a86; }
.emergency { text-align: center; margin: 16px 0; color: #6a5a56; font-size: 14px; }
.emergency b { color: #c04a3f; font-size: 16px; }
.boundary { font-size: 12px; color: #9a8a86; border-top: 1px dashed #eee; padding-top: 12px; line-height: 1.7; }
.hint { text-align: center; color: #9aa1bd; }
</style>
