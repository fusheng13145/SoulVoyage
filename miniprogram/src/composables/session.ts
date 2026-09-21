import { LOGIN_PAGE } from '@/config'
import { useAuthStore } from '@/stores/auth'

/** tab 页共用的进页守卫：本地没有 access token 就直接回登录页，页面内容不再自己判空。
 *  只查本地存储、不打接口：网络层已经负责 401→刷一次→回登录页，页面再打一次 /auth/me 只是多一次冷启动请求。 */
export function ensureSession(): boolean {
  if (useAuthStore().hasToken()) return true
  uni.reLaunch({ url: LOGIN_PAGE })
  return false
}

/** tabBar 页只能用 switchTab 跳：navigateTo 过去会静默失败，页面看起来像"点了没反应" */
export function goTab(url: string) {
  uni.switchTab({ url })
}

export function go(url: string) {
  uni.navigateTo({
    url,
    fail: () => uni.switchTab({ url }),
  })
}
