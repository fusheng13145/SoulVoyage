/** 端侧运行时配置。
 *
 * 小程序里 uni.request 必须写绝对域名，且该域名要在微信后台登记为"request 合法域名"
 * （HTTPS + ICP 备案）——这是本机验证用 H5 产物、而不是直接用微信开发者工具跑真接口的原因：
 * 开发期没有备案域名，链路只能在 :5174 的 H5 目标上真跑。所以下面按端分流：
 * H5 用相对路径走 vite 代理，非 H5（真机小程序）用线上域名占位，上线前替换。 */

/** 线上接口域名：必须是 HTTPS + 已备案域名，并在小程序后台加入合法域名白名单 */
export const ONLINE_ORIGIN = 'https://REPLACE-WITH-BEIAN-DOMAIN'

/** 端判定走运行时而不是 `#ifdef` 编译宏：宏删行后 vue-tsc 看到的仍是两份声明，类型检查会整体失效，
 *  而这个常量决定所有请求发往哪里——宁可运行时问一句，也不要一个没人检查的分支。 */
export const isMiniProgram = /mp-/.test(String(uni.getSystemInfoSync().uniPlatform ?? ''))

/** 真机小程序：绝对域名（须在微信后台白名单内）；H5 本机验证：相对路径交给 vite 代理 */
export const API_BASE = isMiniProgram ? `${ONLINE_ORIGIN}/api/v1` : '/api/v1'

/** 会话与本地态的存储键：命名沿用 Web 端，避免同一套后端字段在两处长出两个名字 */
export const TOKEN_KEY = 'sv_access'
export const REFRESH_KEY = 'sv_refresh'
export const THEME_KEY = 'sv_theme'
/** 本机 H5 验证用的假微信凭证（真小程序由 uni.login 现取，不落本地） */
export const WX_FAKE_CODE_KEY = 'sv_wx_jscode'

export const LOGIN_PAGE = '/pages/login/index'
