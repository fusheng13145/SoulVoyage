<script setup lang="ts">
import { ref } from 'vue'
import { jsCodeHint, rotateJsCode } from '@/api/wx'
import { useTheme } from '@/composables/theme'
import { useAuthStore } from '@/stores/auth'

/**
 * 小程序端入口页（M14）：只做一件事——把人送进既有账号。
 *
 * 三条刻意：
 * ① 没有"用户名+口令"登录分支。微信是本端唯一入口，口令只在"这台微信要进哪个账号"这一步出现一次，
 *    与后端口径一致（微信不建立账号，只做第二个入口）；
 * ② 绑定失败不丢票。1003/1004 之后同一张票还能再交一次，只有 1006（票过期/被用掉）才要求重走授权；
 * ③ 错误文案一律用后端给的中文话术，页面不自己编第二套说法。
 */
const auth = useAuthStore()
const { themeClass } = useTheme()

const busy = ref(false)
const needBind = ref(false)
const mode = ref<'bind' | 'register'>('bind')
const username = ref('')
const password = ref('')
const nickname = ref('')
const err = ref('')
/** 本机 H5 验证才有值：现在顶的是哪个"假微信"，用于把绑定链路走全 */
const wxHint = ref(jsCodeHint())

function failOf(e: unknown): { msg: string; code: number } {
  const err2 = e as { message?: string; code?: number }
  return { msg: err2?.message || '操作没有成功', code: err2?.code ?? 0 }
}

async function signIn() {
  busy.value = true
  err.value = ''
  try {
    const resp = await auth.signInWithWechat()
    if (resp.bound) {
      enter()
      return
    }
    needBind.value = true
  } catch (e) {
    err.value = failOf(e).msg
  } finally {
    busy.value = false
    // 本机假凭证要到第一次点完才生成，提示行到这一刻才有内容可显示
    wxHint.value = jsCodeHint()
  }
}

async function submit() {
  const ticket = auth.bindTicket
  if (!ticket) {
    needBind.value = false
    err.value = '这次授权已经用过了，重新进入一次就好'
    return
  }
  busy.value = true
  err.value = ''
  try {
    if (mode.value === 'bind') {
      await auth.bindExisting(ticket, username.value.trim(), password.value)
    } else {
      await auth.registerWithWechat({
        bindTicket: ticket,
        username: username.value.trim(),
        password: password.value,
        nickname: nickname.value.trim() || undefined,
      })
    }
    enter()
  } catch (e) {
    const { msg, code } = failOf(e)
    // 1006：票是一次性的，过期或被用掉都得重新领一张；其余失败（口令错/重名）票还在原处
    if (code === 1006) {
      needBind.value = false
      err.value = '这次授权过期了，重新点一下进入即可'
    } else {
      err.value = msg
    }
  } finally {
    busy.value = false
  }
}

function enter() {
  uni.reLaunch({ url: '/pages/today/index' })
}

/** 只在 H5 验证用：换一枚本机假凭证，等价于"换成另一个微信号" */
function switchWechat() {
  wxHint.value = rotateJsCode().slice(-4)
  needBind.value = false
  err.value = ''
}
</script>

<template>
  <view class="sv-page login" :class="themeClass">
    <view class="hero">
      <text class="hero-title">心屿漫行</text>
      <text class="sv-muted hero-sub">情绪觉察与陪伴，一天一小步。</text>
    </view>

    <view class="sv-surface card">
      <template v-if="!needBind">
        <text class="card-title">用当前微信进入</text>
        <text class="sv-muted tip">
          微信只是这个账号的第二个入口。第一次进来需要你确认一次身份，之后点开即进。
        </text>
        <button class="sv-btn" :class="{ 'is-disabled': busy }" :disabled="busy" @tap="signIn">
          {{ busy ? '正在进入…' : '微信一键进入' }}
        </button>
        <!-- 真机上 jsCodeHint() 恒为空（凭证由 uni.login 现取、不落本地），这一行自然不出现 -->
        <view v-if="wxHint" class="local-row">
          <text class="sv-cap">本机验证微信：{{ wxHint }}</text>
          <text class="sv-cap link" @tap="switchWechat">换一个微信</text>
        </view>
      </template>

      <template v-else>
        <text class="card-title">这台微信还没接上账号</text>
        <text class="sv-muted tip">选一个：进已有账号，或者为它新建一个账号。</text>

        <view class="seg">
          <text class="seg-item" :class="{ on: mode === 'bind' }" @tap="mode = 'bind'">绑定已有账号</text>
          <text class="seg-item" :class="{ on: mode === 'register' }" @tap="mode = 'register'">
            注册新账号
          </text>
        </view>

        <view class="field">
          <text class="sv-field-label">用户名</text>
          <input v-model="username" class="sv-field" placeholder="字母、数字或下划线，3–32 位" />
        </view>
        <view class="field">
          <text class="sv-field-label">口令</text>
          <input v-model="password" class="sv-field" password placeholder="至少 8 位" />
        </view>
        <view v-if="mode === 'register'" class="field">
          <text class="sv-field-label">昵称（可留空）</text>
          <input v-model="nickname" class="sv-field" placeholder="怎么称呼你" />
        </view>

        <button class="sv-btn" :class="{ 'is-disabled': busy }" :disabled="busy" @tap="submit">
          {{ busy ? '提交中…' : mode === 'bind' ? '绑定并进入' : '创建并进入' }}
        </button>
        <text class="sv-cap fine">
          口令只在确认身份这一步用到：复核证据、改密、注销都要它，所以本端不提供"只有微信、没有口令"的账号。
        </text>
      </template>

      <view v-if="err" class="err">
        <text class="err-text">{{ err }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.login {
  padding-top: calc(var(--sv-s8) + var(--sv-safe-t));
}
.hero {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s1);
  margin-bottom: var(--sv-s6);
}
.hero-title {
  font-size: var(--sv-fs-largename);
  font-weight: 700;
  letter-spacing: -0.6px;
}
.hero-sub {
  font-size: var(--sv-fs-callout);
}
.card {
  display: flex;
  flex-direction: column;
  gap: var(--sv-s3);
}
.card-title {
  font-size: var(--sv-fs-headline);
  font-weight: 700;
}
.tip {
  font-size: var(--sv-fs-subhead);
}
.field {
  display: flex;
  flex-direction: column;
}
.seg {
  display: flex;
  padding: 3px;
  gap: 3px;
  background: var(--sv-fill3);
  border-radius: var(--sv-r-ctl);
}
.seg-item {
  flex: 1;
  padding: 9px 0;
  border-radius: 11px;
  color: var(--sv-label2);
  font-size: var(--sv-fs-subhead);
  text-align: center;
}
.seg-item.on {
  background: var(--sv-card);
  color: var(--sv-label);
  font-weight: 600;
  box-shadow: var(--sv-sh-1);
}
.local-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.link {
  color: var(--sv-indigo);
}
.fine {
  line-height: 1.5;
}
.err {
  padding: var(--sv-s3);
  background: rgba(255, 69, 58, 0.1);
  border-radius: var(--sv-r-ctl);
}
.err-text {
  color: var(--sv-red);
  font-size: var(--sv-fs-footnote);
}
</style>
