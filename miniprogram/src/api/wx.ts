import { isMiniProgram, WX_FAKE_CODE_KEY } from '@/config'

/**
 * 取一次"微信登录凭证"（jscode），交给后端 POST /auth/wechat/login 换 openid。
 *
 * 两端行为刻意不同：
 * ① 真小程序：uni.login 现取。code 五分钟内有效且只能被后端用一次，所以每次登录都重新取，绝不缓存；
 * ② 本机 H5 验证：没有 AppID，换不到真 code，就用一枚持久化的假 code。
 *    假 code 换不到任何账号——后端切到真微信 provider 时它会直接被打回，
 *    只有开发默认的那个 provider 会把它回放成一枚稳定的假 openid。
 *    之所以要"持久化 + 可轮换"，是为了把绑定/解绑/免口令再登录这条链路在浏览器里走全：
 *    稳定 = 同一个身份，轮换 = 换成另一个身份。
 */
export function wxJsCode(): Promise<string> {
  if (isMiniProgram) {
    return new Promise((resolve, reject) => {
      uni.login({
        provider: 'weixin',
        success: res => (res.code ? resolve(res.code) : reject(new Error('微信没给回登录凭证，重试一下'))),
        fail: () => reject(new Error('微信授权没完成，可以稍后再试')),
      })
    })
  }
  return Promise.resolve(localJsCode())
}

function localJsCode(): string {
  const cached = (uni.getStorageSync(WX_FAKE_CODE_KEY) as string) || ''
  if (cached) return cached
  const fresh = rotateJsCode()
  uni.setStorageSync(WX_FAKE_CODE_KEY, fresh)
  return fresh
}

/** 换一枚本机假凭证（等价于"换成另一台手机上的另一个微信"） */
export function rotateJsCode(): string {
  const code = `local-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  uni.setStorageSync(WX_FAKE_CODE_KEY, code)
  return code
}

/** 当前本机身份的可读指纹：只用于"现在是以哪个微信在试"这一提示，不参与任何判定；真机上恒为空 */
export function jsCodeHint(): string {
  const c = (uni.getStorageSync(WX_FAKE_CODE_KEY) as string) || ''
  return c.length > 4 ? c.slice(-4) : ''
}
