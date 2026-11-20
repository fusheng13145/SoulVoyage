import { defineStore } from 'pinia'
import { ref } from 'vue'
import { clearAccessToken, ensureSession, setAccessToken } from '@/api/http'
import { authApi, type Me } from '@/api/domains/auth'

export type { Me, TokenResp } from '@/api/domains/auth'

/** 会话唯一事实源：access 内存令牌、/auth/me、注销冷静期标记 */
export const useAuthStore = defineStore('auth', () => {
  const me = ref<Me | null>(null)
  const loaded = ref(false)
  /** refresh 已交出 HttpOnly Cookie，前端只能靠 access 是否存在判断登录态 */
  const authed = ref(false)

  function adopt(t: { accessToken: string }) {
    setAccessToken(t.accessToken)
    authed.value = true
  }

  async function login(username: string, password: string) {
    const t = await authApi.login(username, password)
    adopt(t)
    flagDeletion(t.deletionPending)
    me.value = null
    await fetchMe()
  }

  async function register(username: string, password: string, nickname: string) {
    const t = await authApi.register(username, password, nickname)
    adopt(t)
    flagDeletion(t.deletionPending)
    me.value = null
    await fetchMe()
  }

  /** 页面刷新后恢复会话：内存 access 已丢，用 Cookie 静默续期一次 */
  async function restore() {
    if (authed.value) return
    if (!(await ensureSession())) return
    authed.value = true
    await fetchMe().catch(() => {
      /* 401 交给拦截器统一处理 */
    })
  }

  function flagDeletion(pending: boolean) {
    if (pending) sessionStorage.setItem('sv_del_pending', '1')
    else sessionStorage.removeItem('sv_del_pending')
  }

  async function fetchMe(force = false) {
    if (me.value && !force) return me.value
    me.value = await authApi.me()
    loaded.value = true
    return me.value
  }

  function clear() {
    clearAccessToken()
    authed.value = false
    sessionStorage.removeItem('sv_del_pending')
    me.value = null
  }

  async function logout() {
    try {
      // 服务端会同时作废 refresh 并下发 Max-Age=0 的 Set-Cookie
      await authApi.logout()
    } catch {
      /* 服务端吊销失败也要本地登出 */
    }
    clear()
  }

  return { me, loaded, authed, login, register, restore, fetchMe, logout, clear, flagDeletion }
})
