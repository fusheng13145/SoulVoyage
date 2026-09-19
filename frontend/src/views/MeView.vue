<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvSegmented from '@/components/ui/SvSegmented.vue'
import SvSwitch from '@/components/ui/SvSwitch.vue'
import { useAuthStore } from '@/stores/auth'
import { useCrisisStore } from '@/stores/crisis'
import { themePref, setTheme, type ThemePref } from '@/composables/theme'
import { hapticOn, setHaptic, confirmDialog } from '@/stores/ui'

const router = useRouter()
const auth = useAuthStore()
const crisis = useCrisisStore()
const loggingOut = ref(false)

onMounted(() => {
  auth.fetchMe().catch(() => { /* 401 由拦截器处理 */ })
  crisis.refreshProfile().catch(() => { /* 横幅降级不影响本页 */ })
})

const me = computed(() => auth.me)
const initial = computed(() => (me.value?.nickname || me.value?.username || '屿').slice(0, 1))

const themeOptions = [
  { label: '跟随', value: 'system' }, { label: '浅色', value: 'light' }, { label: '深色', value: 'dark' },
]
function pickTheme(v: string | number) { setTheme(v as ThemePref) }

async function logout() {
  if (loggingOut.value) return
  const ok = await confirmDialog({ title: '退出登录', message: '下次需要用账号重新进入心屿。' })
  if (!ok) return
  loggingOut.value = true
  await auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="me">
    <SvNavBar title="我的" />

    <div class="body">
      <SvCard>
        <div class="profile">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <div class="pinfo">
            <b>{{ me?.nickname || me?.username || '旅人' }}</b>
            <p class="sv-muted">{{ me?.username ? `@${me.username}` : '' }}</p>
          </div>
        </div>
        <div v-if="me?.status === 3" class="notice">
          账号处于注销冷静期。<router-link to="/account">去撤回 →</router-link>
        </div>
      </SvCard>

      <SvList title="外观与体感">
        <SvCell label="主题" icon="i-moon" tone="var(--sv-indigo-soft)" :chevron="false">
          <template #extra>
            <SvSegmented class="seg-in-cell" :model-value="themePref" :options="themeOptions" @update:model-value="pickTheme" />
          </template>
        </SvCell>
        <SvCell label="触感反馈" hint="点按时轻震一下（危机场景永不震动）" icon="i-sparkle" tone="color-mix(in srgb, var(--e-joy) 20%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="hapticOn" label="触感反馈" @update:model-value="setHaptic" /></template>
        </SvCell>
      </SvList>

      <SvList title="我的记录">
        <SvCell label="成长档案" hint="周报 · 报告 · 限时导出打印" icon="i-folder" tone="color-mix(in srgb, var(--sv-mint) 18%, transparent)" to="/archive" />
        <SvCell label="隐私中心" hint="数据导出 · 修改密码 · 注销即遗忘" icon="i-shield" tone="var(--sv-indigo-soft)" to="/account" />
      </SvList>

      <SvList title="支持">
        <SvCell label="危机资源" hint="随时可连的热线与渠道" icon="i-phone" tone="color-mix(in srgb, var(--sv-red) 14%, transparent)" to="/crisis" />
      </SvList>

      <button class="sv-btn ghost" :disabled="loggingOut" @click="logout">退出登录</button>

      <p class="sv-cap foot">心屿漫行对全部内容做信封加密，服务端无法读取明文。</p>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 var(--sv-s4); }
.profile { display: flex; align-items: center; gap: var(--sv-s4); }
.avatar { width: 56px; height: 56px; flex: none; border-radius: 50%; display: grid; place-items: center;
  font-size: var(--sv-fs-title2); font-weight: 700; color: #fff;
  background: linear-gradient(140deg, var(--sv-indigo), var(--sv-purple)); }
.pinfo b { font-size: var(--sv-fs-title3); display: block; }
.notice { margin-top: var(--sv-s3); background: color-mix(in srgb, var(--sv-amber) 16%, var(--sv-card));
  border-radius: var(--sv-r-ctl); padding: 10px 12px; font-size: var(--sv-fs-footnote); }
.seg-in-cell { min-width: 168px; }
.foot { text-align: center; margin-top: var(--sv-s5); line-height: 1.6; }
</style>
