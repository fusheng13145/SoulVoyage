import { defineStore } from 'pinia'
import { ref } from 'vue'
import { TOKEN_KEY, REFRESH_KEY } from '@/api/http'
import { authApi, type Me } from '@/api/domains/auth'

export type { Me, TokenResp } from '@/api/domains/auth'

/** 会话唯一事实源：token 存取、/auth/me、注销冷静期标记 */
export const useAuthStore = defineStore('auth', () => {
  const me = ref<Me | null>(null)
  const loaded = ref(false)

  function setTokens(t: { accessToken: string; refreshToken: string }) {
    localStorage.setItem(TOKEN_KEY, t.accessToken)
    localStorage.setItem(REFRESH_KEY, t.refreshToken)
  }

  async function login(username: string, password: string) {
    const t = await authApi.login(username, password)
    setTokens(t)
    flagDeletion(t.deletionPending)
    me.value = null
    await fetchMe()
  }

  async function register(username: string, password: string, nickname: string) {
    const t = await authApi.register(username, password, nickname)
    setTokens(t)
    flagDeletion(t.deletionPending)
    me.value = null
    await fetchMe()
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
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    sessionStorage.removeItem('sv_del_pending')
    me.value = null
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      /* 服务端吊销失败也要本地登出 */
    }
    clear()
  }

  return { me, loaded, login, register, fetchMe, logout, clear, flagDeletion }
})
