import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  fetchMe,
  logout as logoutApi,
  wechatBind,
  wechatLogin,
  wechatRegister,
  wechatUnbind,
  type Me,
  type TokenResp,
  type WxLoginResp,
} from '@/api/auth'
import { clearTokens, getRefreshToken, getToken, setTokens } from '@/api/http'
import { wxJsCode } from '@/api/wx'

/** 会话唯一事实源（对齐 Web 端 stores/auth）：令牌只经这一处进出存储，页面不直接碰 storage。
 *
 *  与 Web 端的差别只有一处：入口是微信，口令只在"绑定/注册"这一步出现一次。
 *  所以这里没有 username/password 登录分支——那条分支在本端不存在，
 *  后端也只把微信当作既有账号的第二个入口。 */
export const useAuthStore = defineStore('auth', () => {
  const me = ref<Me | null>(null)
  const ready = ref(false)
  /** 未绑定微信带来的那一次性票：留在 store 里是为了让"填错口令"不必重走一遍微信授权 */
  const bindTicket = ref('')

  /** 管理端刻意不进小程序：管理员在本端只作为普通用户，需要看板请回 Web */
  const isAdmin = computed(() => me.value?.role === 'ADMIN')
  /** 冷静期由 /auth/me 的 status 判定，不在本地另存一份标记——少一个会和库不一致的真相源 */
  const deletionPending = computed(() => me.value?.status === 3)

  /** 有没有登录态：存储不是响应式的，所以给页面一个显式的判断函数，而不是假装它是 computed */
  function hasToken(): boolean {
    return !!getToken()
  }

  function applyToken(t: TokenResp) {
    setTokens({ accessToken: t.accessToken, refreshToken: t.refreshToken })
    bindTicket.value = ''
    me.value = null
  }

  /** 冷启动：有令牌就先认一次人，认不过（401）由网络层统一登出，这里只负责不拦住首屏 */
  async function bootstrap() {
    if (!getToken()) {
      ready.value = true
      return null
    }
    await fetchMeOnce(true)
    return me.value
  }

  async function fetchMeOnce(force = false): Promise<Me | null> {
    if (me.value && !force) return me.value
    try {
      me.value = await fetchMe()
    } catch {
      me.value = null
    } finally {
      ready.value = true
    }
    return me.value
  }

  /** 微信一键进入：已绑定即拿到令牌；未绑定只拿到票，由页面引导去绑定或注册 */
  async function signInWithWechat(): Promise<WxLoginResp> {
    const resp = await wechatLogin(await wxJsCode())
    if (resp.bound && resp.token) applyToken(resp.token)
    else bindTicket.value = resp.bindTicket || ''
    await fetchMeOnce(true)
    return resp
  }

  async function bindExisting(bindTicketValue: string, username: string, password: string) {
    applyToken(await wechatBind(bindTicketValue, username, password))
    await fetchMeOnce(true)
  }

  async function registerWithWechat(input: {
    bindTicket: string
    username: string
    password: string
    nickname?: string
  }) {
    applyToken(await wechatRegister(input))
    await fetchMeOnce(true)
  }

  /** 解绑：微信入口收回去，账号和数据都还在（口令仍是真源），所以本地只清身份不回登录页 */
  async function unbindWechat() {
    await wechatUnbind()
    await fetchMeOnce(true)
  }

  async function signOut() {
    try {
      await logoutApi(getRefreshToken())
    } catch {
      /* 服务端吊销失败也要本地登出：留着半死不活的会话更糟 */
    }
    clear()
  }

  function clear() {
    clearTokens()
    me.value = null
    bindTicket.value = ''
    ready.value = true
  }

  return {
    me,
    ready,
    bindTicket,
    hasToken,
    isAdmin,
    deletionPending,
    bootstrap,
    fetchMeOnce,
    signInWithWechat,
    bindExisting,
    registerWithWechat,
    unbindWechat,
    signOut,
    clear,
  }
})
