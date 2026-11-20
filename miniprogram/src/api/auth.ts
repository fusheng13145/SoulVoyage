import { del, get, post } from '@/api/http'
import type { components } from '@/api/schema'

/** 登录/会话接口封装（M14）。
 *  请求体一律用 OpenAPI 生成物钉形状（npm run gen:api）：字段名写错、少传约束项，
 *  在 `vue-tsc` 阶段就红，而不是等真机回一句 2001 参数不合法再回头猜。 */
type Body<B extends object, K extends keyof components['schemas']> = B & [B] extends [
  components['schemas'][K],
]
  ? unknown
  : never

/** 后端 TokenResp：deletionPending=true 表示账号在注销冷静期，登录后要立刻提示撤回 */
export interface TokenResp {
  accessToken: string
  /** H5/浏览器端为 null（refresh 在 HttpOnly Cookie 里），只有原生小程序才拿得到 */
  refreshToken: string | null
  userId: number
  nickname: string
  role: string
  deletionPending: boolean
}

/** 微信登录回执两态：bound=true 直接有令牌；bound=false 只有一张一次性绑定票（openid 永不下发） */
export interface WxLoginResp {
  bound: boolean
  bindTicket?: string
  ticketExpiresInSeconds?: number
  token?: TokenResp
}

/** /auth/me 的元数据视图：wechatBound 只是布尔，微信标识不在任何用户可读字段里 */
export interface Me {
  userId: number
  username: string
  nickname: string
  role: string
  status: number
  crisisState: string
  agreedPolicyAt: string
  policyVersion: string
  deletionRequestedAt: string
  wechatBound: boolean
  createdAt: string
}

export const wechatLogin = (code: string) =>
  post<WxLoginResp>('/auth/wechat/login', { code } satisfies Body<{ code: string }, 'WxLoginReq'>)

export const wechatBind = (bindTicket: string, username: string, password: string) =>
  post<TokenResp>('/auth/wechat/bind', { bindTicket, username, password } satisfies Body<
    { bindTicket: string; username: string; password: string },
    'WxBindReq'
  >)

export const wechatRegister = (input: {
  bindTicket: string
  username: string
  password: string
  nickname?: string
}) => post<TokenResp>('/auth/wechat/register', input satisfies Body<typeof input, 'WxRegisterReq'>)

/** 收回第三方入口：必须带登录态，未绑定回 2001，不做"静默成功" */
export const wechatUnbind = () => del<null>('/auth/wechat/binding')

export const fetchMe = () => get<Me>('/auth/me')

/** 登出要把 refreshToken 一起交回去：只拉黑 access 的话，旧 refresh 还能再换一副新令牌。
 *  H5 端 refresh 在 Cookie 里，body 传空即可，后端会回落读 Cookie 并下发清除。 */
export const logout = (refreshToken?: string) =>
  post<null>('/auth/logout', refreshToken ? { refreshToken } : {})
