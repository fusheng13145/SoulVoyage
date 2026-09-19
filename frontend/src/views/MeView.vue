<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import SvNavBar from '@/components/ui/SvNavBar.vue'
import SvCard from '@/components/ui/SvCard.vue'
import SvList from '@/components/ui/SvList.vue'
import SvCell from '@/components/ui/SvCell.vue'
import SvSegmented from '@/components/ui/SvSegmented.vue'
import SvSwitch from '@/components/ui/SvSwitch.vue'
import http, { type ApiResp } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useCrisisStore } from '@/stores/crisis'
import { themePref, setTheme, type ThemePref } from '@/composables/theme'
import { hapticOn, setHaptic, confirmDialog, toast } from '@/stores/ui'

interface Prefs { theme: string; checkinReminderOn: boolean; reminderTime: string; planReminderOn: boolean;
  letterOn: boolean; hapticOn: boolean; companionAnalysisOn: boolean }

const router = useRouter()
const auth = useAuthStore()
const crisis = useCrisisStore()
const loggingOut = ref(false)
const prefs = ref<Prefs | null>(null)

onMounted(() => {
  auth.fetchMe().catch(() => { /* 401 由拦截器处理 */ })
  crisis.refreshProfile().catch(() => { /* 横幅降级不影响本页 */ })
  http.get<ApiResp<Prefs>>('/preferences').then(({ data }) => {
    prefs.value = data.data
    // 服务端为真源：首次对齐本地缓存
    if (data.data.theme !== themePref.value) setTheme(data.data.theme as ThemePref)
    if (data.data.hapticOn !== hapticOn.value) setHaptic(data.data.hapticOn)
  }).catch(() => { /* 离线时保留本地偏好 */ })
})

const me = computed(() => auth.me)
const initial = computed(() => (me.value?.nickname || me.value?.username || '屿').slice(0, 1))

const themeOptions = [
  { label: '跟随', value: 'system' }, { label: '浅色', value: 'light' }, { label: '深色', value: 'dark' },
]
function pickTheme(v: string | number) {
  setTheme(v as ThemePref)
  patch({ theme: String(v) })
}

/** 单项 PUT：失败回滚由重新拉取兜底（简单起见仅提示） */
async function patch(body: Partial<Prefs>) {
  if (!prefs.value) return
  try {
    const { data } = await http.put<ApiResp<Prefs>>('/preferences', body)
    prefs.value = data.data
  } catch (e: any) {
    toast(e.message || '设置没保存上，再试一次')
  }
}

function toggle(key: keyof Prefs, v: boolean) {
  if (!prefs.value) return
  prefs.value = { ...prefs.value, [key]: v }
  if (key === 'hapticOn') setHaptic(v)
  patch({ [key]: v } as Partial<Prefs>)
}

function pickTime(e: Event) {
  if (!prefs.value) return
  prefs.value = { ...prefs.value, reminderTime: (e.target as HTMLInputElement).value }
  patch({ reminderTime: prefs.value.reminderTime })
}

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

      <SvList title="屿上的痕迹">
        <SvCell label="成长来信" hint="每周一封 · 只引用你说过的话" icon="i-mail" tone="color-mix(in srgb, var(--sv-amber) 14%, transparent)" to="/letters" />
        <SvCell label="成就墙" hint="每一枚都来自照顾自己的时刻" icon="i-medal" tone="color-mix(in srgb, var(--e-joy) 20%, transparent)" to="/achievements" />
        <SvCell label="通知中心" hint="提醒 · 计划 · 来信到会在这里等你" icon="i-bell" tone="var(--sv-indigo-soft)" to="/notifications" />
      </SvList>

      <SvList title="外观与体感">
        <SvCell label="主题" icon="i-moon" tone="var(--sv-indigo-soft)" :chevron="false">
          <template #extra>
            <SvSegmented class="seg-in-cell" :model-value="themePref" :options="themeOptions" @update:model-value="pickTheme" />
          </template>
        </SvCell>
        <SvCell label="触感反馈" hint="点按时轻震一下（危机场景永不震动）" icon="i-sparkle" tone="color-mix(in srgb, var(--e-joy) 20%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="hapticOn" label="触感反馈" @update:model-value="toggle('hapticOn', $event)" /></template>
        </SvCell>
      </SvList>

      <SvList title="它如何提醒你">
        <SvCell label="每日打卡提醒" hint="到点轻轻说一句，不连环催" icon="i-clock" tone="color-mix(in srgb, var(--sv-mint) 18%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="prefs?.checkinReminderOn ?? false" label="打卡提醒" :disabled="!prefs" @update:model-value="toggle('checkinReminderOn', $event)" /></template>
        </SvCell>
        <SvCell v-if="prefs" label="提醒时间" icon="i-bell" tone="var(--sv-indigo-soft)" :chevron="false">
          <template #extra>
            <input class="time" type="time" :value="prefs.reminderTime" aria-label="打卡提醒时间" :disabled="!prefs.checkinReminderOn" @change="pickTime" />
          </template>
        </SvCell>
        <SvCell label="计划跟练提醒" hint="今天有计划项没做时提醒一次" icon="i-target" tone="color-mix(in srgb, var(--sv-mint) 18%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="prefs?.planReminderOn ?? false" label="计划提醒" :disabled="!prefs" @update:model-value="toggle('planReminderOn', $event)" /></template>
        </SvCell>
        <SvCell label="成长来信" hint="周一早上 07:00 寄出" icon="i-mail" tone="color-mix(in srgb, var(--sv-amber) 14%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="prefs?.letterOn ?? false" label="成长来信" :disabled="!prefs" @update:model-value="toggle('letterOn', $event)" /></template>
        </SvCell>
        <SvCell label="漫聊情绪消化" hint="关掉后收段只做归档，不做情绪分析；单句仍可用「这句别分析」" icon="i-chat" tone="color-mix(in srgb, var(--sv-purple, var(--sv-indigo)) 14%, transparent)" :chevron="false">
          <template #extra><SvSwitch :model-value="prefs?.companionAnalysisOn ?? false" label="漫聊分析" :disabled="!prefs" @update:model-value="toggle('companionAnalysisOn', $event)" /></template>
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
.time { border: 1px solid var(--sv-sep); border-radius: 8px; background: var(--sv-card); color: var(--sv-label);
  font-size: var(--sv-fs-footnote); padding: 6px 8px; font-family: inherit; }
.foot { text-align: center; margin-top: var(--sv-s5); line-height: 1.6; }
</style>
