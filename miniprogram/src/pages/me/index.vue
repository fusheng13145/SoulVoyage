<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { cancelDelete, prefs as fetchPrefs, putPrefs, requestDelete, type Preferences } from '@/api/app'
import { LOGIN_PAGE } from '@/config'
import { applyTheme, useTheme } from '@/composables/theme'
import { ensureSession, go } from '@/composables/session'
import { useAuthStore } from '@/stores/auth'
import { confirmSheet, toast } from '@/utils/feedback'

/** 我的：这一页只管三件事——身份入口、被看到的范围、离开的路。
 *  管理端与练习室刻意不在小程序里：辅导员侧的一切留在网页端。 */
const auth = useAuthStore()
const { mode, isDark, themeClass, setMode } = useTheme()

const p = ref<Preferences | null>(null)
const showDelete = ref(false)
const pwd = ref('')
const busy = ref(false)

const crisis = computed(() => !!auth.me && auth.me.crisisState !== 'NORMAL')

onShow(() => {
  applyTheme()
  if (ensureSession()) void load()
})

async function load() {
  await auth.fetchMeOnce(true)
  try {
    p.value = await fetchPrefs()
    // 偏好里的 theme 是唯一真源：本地存的模式和它不一致时以库为准
    const want = p.value.theme === 'dark' ? 'dark' : p.value.theme === 'light' ? 'light' : 'auto'
    if (want !== mode.value) setMode(want)
  } catch {
    p.value = null
  }
}

async function patch(key: keyof Preferences, value: boolean | string) {
  if (!p.value || busy.value) return
  busy.value = true
  const prev = p.value[key]
  try {
    p.value = await putPrefs({ [key]: value } as Partial<Preferences>)
  } catch (e) {
    p.value = { ...p.value, [key]: prev } as Preferences
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function chooseTheme(next: 'auto' | 'light' | 'dark') {
  setMode(next)
  await putPrefs({ theme: next === 'auto' ? 'system' : next }).catch(() => {})
  if (p.value) p.value.theme = next === 'auto' ? 'system' : next
}

async function unbind() {
  if (
    !(await confirmSheet(
      '收回微信入口',
      '收回后就不能再用微信一键进来了；账号和数据都还在，可以用网页端登录。',
    ))
  )
    return
  try {
    await auth.unbindWechat()
    toast('已收回')
    await load()
  } catch (e) {
    toast((e as Error).message)
  }
}

async function applyDelete() {
  if (!pwd.value) {
    toast('请先输入当前口令')
    return
  }
  busy.value = true
  try {
    await requestDelete(pwd.value)
    pwd.value = ''
    showDelete.value = false
    toast('已进入 7 天冷静期，随时可以回来撤回')
    await load()
  } catch (e) {
    toast((e as Error).message)
  } finally {
    busy.value = false
  }
}

async function withdrawDelete() {
  if (!(await confirmSheet('撤回注销', '账号回到正常状态，冷静期内记下的东西都还在。'))) return
  try {
    await cancelDelete()
    toast('回来了')
    await load()
  } catch (e) {
    toast((e as Error).message)
  }
}

async function signOut() {
  await auth.signOut()
  uni.reLaunch({ url: LOGIN_PAGE })
}

/** switch 的回调值只在 detail 里；$event 的类型声明是通用 Event，这里显式收一次口 */
function switched(e: Event): boolean {
  return !!(e as unknown as { detail?: { value?: boolean } }).detail?.value
}
</script>

<template>
  <view class="sv-page me" :class="themeClass">
    <text class="sv-h1">我的</text>
    <text class="sv-muted">这里放的都跟"谁能看到你"有关。</text>

    <view class="sv-surface id">
      <view class="line">
        <text class="label">昵称</text>
        <text class="value">{{ auth.me?.nickname || auth.me?.username || '—' }}</text>
      </view>
      <view class="line">
        <text class="label">账号</text>
        <text class="value">{{ auth.me?.username || '—' }}</text>
      </view>
      <view class="line">
        <text class="label">状态</text>
        <text class="value">{{ auth.deletionPending ? '注销冷静期' : '正常' }}</text>
      </view>
      <text v-if="auth.isAdmin" class="sv-cap hint"
        >你是辅导员/管理员：看板、审核和练习室请在网页端使用，小程序不复制那一套。</text
      >
    </view>

    <view class="sv-surface block">
      <text class="sv-h2">进入方式</text>
      <view class="line">
        <text class="label">微信入口</text>
        <text class="value">{{ auth.me?.wechatBound ? '已绑定' : '未绑定' }}</text>
      </view>
      <button v-if="auth.me?.wechatBound" class="sv-btn ghost" @tap="unbind">收回微信入口</button>
      <text v-else class="sv-cap hint">没绑微信也可以：网页端口令登录一直是主路。</text>
    </view>

    <view class="sv-surface block">
      <text class="sv-h2">被看到的范围</text>
      <view class="switch-line">
        <view class="switch-text">
          <text class="label">参与辅导员群体统计</text>
          <text class="sv-cap">只进 10 人以上群体的均值，永远看不到你写下的原话。</text>
        </view>
        <switch
          :checked="!!p?.counselorBoardOn"
          color="#5b6cff"
          @change="patch('counselorBoardOn', switched($event))"
        />
      </view>
      <view class="switch-line">
        <view class="switch-text">
          <text class="label">让树洞的话进入梳理</text>
          <text class="sv-cap">关掉后收段不再回看这段对话，聊天本身照常。</text>
        </view>
        <switch
          :checked="p ? !!p.companionAnalysisOn : true"
          color="#5b6cff"
          @change="patch('companionAnalysisOn', switched($event))"
        />
      </view>
      <text v-if="!p" class="sv-cap hint">偏好没读到，稍后下拉再看一次。</text>
    </view>

    <view class="sv-surface block">
      <text class="sv-h2">外观</text>
      <view class="seg">
        <text class="seg-item" :class="{ on: mode === 'auto' }" @tap="chooseTheme('auto')">跟系统</text>
        <text class="seg-item" :class="{ on: mode === 'light' }" @tap="chooseTheme('light')">浅色</text>
        <text class="seg-item" :class="{ on: isDark }" @tap="chooseTheme('dark')">深色</text>
      </view>
    </view>

    <view class="sv-surface block">
      <text class="sv-h2">数据与离开</text>
      <text class="sv-muted">
        打卡的一句话、日记正文、树洞里的原话都以只有你解得开的方式存放。导出、注销与密钥相关的事在网页端「设置」里做全。
      </text>
      <view v-if="auth.deletionPending" class="actions">
        <button class="sv-btn" :class="{ 'is-disabled': busy }" @tap="withdrawDelete">撤回注销申请</button>
      </view>
      <view v-else class="actions">
        <button class="sv-btn plain danger-text" @tap="showDelete = !showDelete">
          {{ showDelete ? '算了' : '申请注销账号' }}
        </button>
      </view>
      <view v-if="showDelete && !auth.deletionPending" class="delete-box">
        <text class="sv-cap"
          >注销要口令确认。先进 7 天冷静期，期间可以随时撤回；到期后密文内容永久不可解。</text
        >
        <input v-model="pwd" class="sv-field" password placeholder="当前口令" placeholder-class="ph" />
        <button class="sv-btn danger" :class="{ 'is-disabled': busy }" @tap="applyDelete">
          确认进入冷静期
        </button>
      </view>
    </view>

    <view class="sv-surface block links">
      <text class="link-row" @tap="go('/pages/notifications/index')">通知</text>
      <text class="link-row" @tap="go('/pages/resources/index')">求助资源</text>
    </view>

    <button class="sv-btn ghost" @tap="signOut">退出登录</button>
    <text v-if="crisis" class="sv-cap hint"
      >你仍在危机期内：需要立刻有人接住时，「求助资源」那一页随时打开。</text
    >
    <view class="tab-spacer" />
  </view>
</template>

<style scoped>
.me {
  padding-top: calc(var(--sv-s4) + var(--sv-safe-t));
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.sv-h1 {
  margin-bottom: var(--sv-s1);
}
.line {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: var(--sv-s2);
  padding: var(--sv-s1) 0;
}
.label {
  font-size: var(--sv-fs-subhead);
}
.value {
  font-size: var(--sv-fs-subhead);
  font-weight: 600;
  color: var(--sv-label2);
}
.block {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
}
.hint {
  line-height: 1.5;
}
.switch-line {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: var(--sv-s3);
  padding: var(--sv-s1) 0;
}
.switch-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}
.seg {
  display: flex;
  flex-direction: row;
  gap: var(--sv-s2);
}
.seg-item {
  flex: 1;
  padding: 8px 0;
  border-radius: var(--sv-r-ctl);
  background: var(--sv-fill3);
  color: var(--sv-label2);
  font-size: var(--sv-fs-footnote);
  text-align: center;
}
.seg-item.on {
  background: var(--sv-indigo-soft);
  color: var(--sv-indigo);
  font-weight: 600;
}
.actions {
  display: flex;
  flex-direction: row;
}
.delete-box {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s2);
  padding-top: var(--sv-s2);
  border-top: 1px solid var(--sv-sep);
}
.delete-box .sv-cap {
  line-height: 1.5;
}
.danger-text {
  color: var(--sv-red);
}
.links {
  gap: 0;
}
.link-row {
  padding: var(--sv-s3) 0;
  font-size: var(--sv-fs-subhead);
  color: var(--sv-indigo);
}
.link-row + .link-row {
  border-top: 1px solid var(--sv-sep);
}
.ph {
  color: var(--sv-label3);
}
.tab-spacer {
  height: var(--sv-s4);
}
</style>
