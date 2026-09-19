import { defineStore } from 'pinia'
import { ref } from 'vue'
import http, { TOKEN_KEY, REFRESH_KEY, type ApiResp } from '@/api/http'

export interface Me {
  userId: number; username: string; nickname: string; role: string
  status: number; deletionRequestedAt: string
}
export interface TokenResp {
  accessToken: string; refreshToken: string; userId: number
  nickname: string; role: string; deletionPending: boolean
}

/** 会话唯一事实源：token 存取、/auth/me、注销冷静期标记 */
export const useAuthStore = defineStore('auth', () => {
  const me = ref<Me | null>(null)
  const loaded = ref(false)

  function setTokens(t: { accessToken: string; refreshToken: string }) {
    localStorage.setItem(TOKEN_KEY, t.accessToken)
    localStorage.setItem(REFRESH_KEY, t.refreshToken)
  }

  async function login(username: string, password: string) {
    const { data } = await http.post<ApiResp<TokenResp>>('/auth/login', { username, password })
    setTokens(data.data)
    flagDeletion(data.data.deletionPending)
    me.value = null
    await fetchMe()
  }

  async function register(username: string, password: string, nickname: string) {
    const { data } = await http.post<ApiResp<TokenResp>>('/auth/register', { username, password, nickname })
    setTokens(data.data)
    flagDeletion(data.data.deletionPending)
    me.value = null
    await fetchMe()
  }

  function flagDeletion(pending: boolean) {
    if (pending) sessionStorage.setItem('sv_del_pending', '1')
    else sessionStorage.removeItem('sv_del_pending')
  }

  async function fetchMe(force = false) {
    if (me.value && !force) return me.value
    const { data } = await http.get<ApiResp<Me>>('/auth/me')
    me.value = data.data
    loaded.value = true
    return data.data
  }

  function clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    sessionStorage.removeItem('sv_del_pending')
    me.value = null
  }

  async function logout() {
    try { await http.post('/auth/logout', {}) } catch { /* 服务端吊销失败也要本地登出 */ }
    clear()
  }

  return { me, loaded, login, register, fetchMe, logout, clear, flagDeletion }
})
