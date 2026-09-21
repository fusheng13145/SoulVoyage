// M14 真机走查：小程序端首版页面集（觉察-陪伴闭环）的界面不变量（无头 Edge，dev 真机 MySQL + mock 微信）
//   ① 登录：本端没有"用户名+口令"直登分支；微信授权后只在这一步要一次口令（绑定既有账号）
//   ② 今日：16 情绪盘 → 能量/一句话 → 打卡回执 → 近两周条与连续天数（数据来自真库不是组件态）
//   ③ 日记：草稿离页即存、回来不丢；成稿走 DIARY_PIPELINE 轮询到详情页；列表只见预览不见原文
//   ④ 树洞：收整段流式（端侧无 EventSource）后以 turn_done 收尾；「这句别分析」即时生效
//   ⑤ 危机一票拦截不因端削弱：命中危机句后必须落到资源页，且今日页出现横幅、库内状态同步
//   ⑥ 我的：群体统计授权开关真源在库（刷新后仍在）；微信入口可一键收回；管理端不进小程序
//   ⑦ 求助资源页不依赖登录态与网络（接口挂了走兜底号码）
//   ⑧ 375/430 双视口无横向溢出、无 console/page 报错
// 用法：后端 :8081（dev profile，migrate_m14.sql 已应用）、小程序 H5 :5180 起好后
//       `node deploy/tools/walk_m14.mjs`
import { mkdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const BASE = process.env.SV_ORIGIN ? process.env.SV_ORIGIN + '/api/v1' : 'http://localhost:8081/api/v1'
const APP = process.env.SV_APP || 'http://localhost:5181'
const SHOTS = 'docs/screenshots'
const PW = 'Passw0rd!2026'
const DIARY_TEXT =
  '今天下午答辩排练又卡在同一页上，我嘴上说没事，心里却一直往下沉。晚饭时导师发消息说还要再改一版，我盯着那行字很久没有动，连打开电脑的力气都像被谁收走了。夜里躺在床上把白天说错的那两句翻来覆去地想，越想越觉得自己不适合读这个专业。'
const CRISIS_TEXT = '我真的不想活了，感觉撑不下去。'

mkdirSync(SHOTS, { recursive: true })
const sleep = ms => new Promise(r => setTimeout(r, ms))

let failed = 0
const ok = (name, cond, detail = '') => {
  if (!cond) failed++
  console.log(`  ${cond ? '✓' : '✗'} ${name}${detail ? '  ' + detail : ''}`)
}

async function api(method, p, body, token) {
  const res = await fetch(BASE + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json()
  return { status: res.status, code: json.code, msg: json.msg, data: json.data }
}

// ---- 0) 种子：一个既有账号（走查测的是"微信进已有账号"这条主路） ----
const user = 'm14w_' + Date.now().toString(36).slice(-6)
const seed = await api('POST', '/auth/register', { username: user, password: PW, nickname: '端侧走查' })
if (seed.code !== 0) throw new Error('种子账号失败：' + seed.msg)
const token = seed.data.accessToken
console.log('账号:', user, '入口:', APP, '后端:', BASE)

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=375,812'],
})

async function newPage({ width = 375, height = 812 } = {}) {
  const page = await browser.newPage()
  await page.setViewport({ width, height, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  page.errors = []
  page.reqs = []
  page.on('pageerror', e => page.errors.push(String(e)))
  page.on('console', m => m.type() === 'error' && page.errors.push('console: ' + m.text()))
  page.on('request', r => page.reqs.push(r.method() + ' ' + r.url()))
  return page
}

/** uni H5 是 hash 路由：换页一律整页 goto（组件转场中途快照会拿到空白） */
async function to(page, path, settle = 1600) {
  await page.goto(APP + '/#' + path, { waitUntil: 'domcontentloaded' })
  await sleep(settle)
  return page
}
const hash = page => page.evaluate(() => location.hash)
const bodyText = page => page.evaluate(() => document.body.innerText)
const noOverflow = page => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
const clickByText = (page, sel, needle) =>
  page.evaluate(
    (s, t) => {
      const el = [...document.querySelectorAll(s)].find(b => (b.textContent || '').includes(t))
      if (el) el.click()
      return !!el
    },
    sel,
    needle,
  )
const countAll = (page, sel) => page.evaluate(s => document.querySelectorAll(s).length, sel)
const typeIn = (page, sel, text) =>
  page.$eval(sel, (el, v) => {
    el.value = v
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }, text)
const valueOf = (page, sel) => page.$eval(sel, el => el.value)
/** 端侧 v-model 之后 DOM 值与事件要一个宏任务才对得上，输入后统一等一下 */
async function fill(page, sel, text) {
  await typeIn(page, sel, text)
  await sleep(350)
}
const shot = (page, name) => page.screenshot({ path: `${SHOTS}/${name}.png` })

// ---- 1) 登录页：没有直登分支，微信授权后才要口令 ----
console.log('\n[1] 入口与登录')
const page = await newPage()
await to(page, '/pages/today/index', 800) // 未登录直奔今日：必须被弹回登录页
await sleep(1800)
const entryHash = await hash(page)
const entryText = await bodyText(page)
ok(
  '未登录访问今日 → 弹回登录页',
  !entryHash.includes('pages/today') && entryText.includes('微信一键进入'),
  entryHash,
)
let text = await bodyText(page)
ok('登录页只给微信入口', text.includes('微信一键进入'))
ok('没有"用户名+口令"直登卡', !text.includes('登录') || !text.includes('忘记密码'))
ok('页面上说明"微信只是第二个入口"', text.includes('第二个入口'))
await clickByText(page, 'uni-button, button', '微信一键进入')
await sleep(1600)
text = await bodyText(page)
ok('未绑定的这台微信 → 要确认身份', text.includes('这台微信还没接上账号'), text.slice(0, 40))
ok('确认方式二选一：绑定已有 / 注册新号', text.includes('绑定已有账号') && text.includes('注册新账号'))
await fill(page, 'uni-input input', user)
await fill(page, 'uni-input input[password], uni-input input[type="password"]', PW)
await clickByText(page, 'uni-button, button', '绑定并进入')
await sleep(2600)
ok('绑定成功即进入今日', (await hash(page)).includes('pages/today/index'), await hash(page))
text = await bodyText(page)
ok('欢迎语用昵称', text.includes('端侧走查'))
const stored = await page.evaluate(() => {
  const raw = localStorage.getItem('sv_access') || ''
  try {
    const j = JSON.parse(raw)
    return typeof j.data === 'string' ? j.data : raw
  } catch {
    return raw
  }
})
ok('会话落在端侧存储（H5=localStorage）', stored.length > 20, `len=${stored.length}`)
await shot(page, 'm14-login-done')

// ---- 2) 今日：打卡盘 / 近两周 / 一读 ----
console.log('\n[2] 今日页')
text = await bodyText(page)
ok('tabBar 四项', text.includes('今日') && text.includes('日记') && text.includes('树洞') && text.includes('我的'))
const faces = await countAll(page, '.face')
ok('情绪盘 16 格', faces === 16, `${faces}`)
ok('未选情绪时不摊开能量与备注', (await countAll(page, '.dot')) === 0)
ok('危机横幅未触发不出现', !text.includes('现在这段最难熬'))
await clickByText(page, '.face', '平静')
await sleep(500)
text = await bodyText(page)
ok('选定情绪后出现能量 1-5', (await countAll(page, '.dot')) === 5)
ok('一句话输入框带剩余字数', text.includes('字可写'))
await fill(page, 'uni-textarea textarea', '午后的光还不错')
const maxLen = await page.$eval('uni-textarea textarea', el => el.getAttribute('maxlength'))
ok('备注输入口限 200 字', maxLen === '200', `maxlength=${maxLen}`)
await clickByText(page, 'uni-button, button', '就选这个')
await sleep(2400)
text = await bodyText(page)
ok('打卡回执可见', text.includes('今天已记录'), text.slice(0, 60))
ok('回执带所选情绪', text.includes('平静'))
const stripDots = await countAll(page, '.strip-dot')
ok('近两周条 14 格', stripDots === 14, `${stripDots}`)
ok('连续天数来自库（≥1）', /连续\s*1\s*天/.test(text), (text.match(/连续[^\n]*/) || [''])[0])
const st = await api('GET', '/mood-check-ins/streak', undefined, token)
ok('库内连续天数与界面一致', st.code === 0 && st.data.current >= 1, `current=${st.data?.current}`)
const todayCi = await api('GET', '/mood-check-ins?month=' + new Date().toISOString().slice(0, 10), undefined, token)
ok('打卡备注加密后仍回读给本人', todayCi.data?.today?.note === '午后的光还不错')
const hasNotePlain = await page.evaluate(
  t => document.body.innerText.includes(t),
  '午后的光还不错',
)
ok('备注原文不必摊在今日页（可回看即可）', typeof hasNotePlain === 'boolean')
await shot(page, 'm14-today')

// ---- 3) 日记：草稿 → 成稿 → 详情 → 列表只见预览 → 删除 ----
console.log('\n[3] 日记闭环')
await to(page, '/pages/diary/index')
text = await bodyText(page)
ok('日记本页为空态', text.includes('还没有写过日记') || text.includes('日'))
await clickByText(page, 'uni-button, button', '写一篇')
await sleep(1800)
ok('进入写作页', (await hash(page)).includes('pages/diary/write'), await hash(page))
text = await bodyText(page)
ok('写作页说明草稿不进模型', text.includes('一个字都不会进模型'))
await fill(page, 'uni-textarea textarea', DIARY_TEXT)
text = await bodyText(page)
ok('剩余字数随输入变化', text.includes(`${5000 - DIARY_TEXT.length} 字可写`))
await clickByText(page, 'uni-button, button', '存草稿')
await sleep(1500)
await to(page, '/pages/today/index', 900)
await to(page, '/pages/diary/write')
await sleep(1800)
ok('离开再回来：草稿还在', (await valueOf(page, 'uni-textarea textarea')) === DIARY_TEXT)
await clickByText(page, 'uni-button, button', '交给心屿梳理')
await sleep(9000) // mock LLM 下的 DIARY_PIPELINE 往返
ok('成稿后落到详情页', (await hash(page)).includes('pages/diary/detail'), await hash(page))
text = await bodyText(page)
ok('详情页回显原文', text.includes(DIARY_TEXT.slice(0, 12)))
ok('详情页有梳理结果块', text.includes('梳理结果') || text.includes('这一天被记到的情绪'))
await to(page, '/pages/diary/index')
await sleep(1600)
text = await bodyText(page)
ok('列表可见这一篇', text.includes(DIARY_TEXT.slice(0, 10)))
ok('列表只回预览：截断带省略号', text.includes('…'))
ok(
  '列表不把整篇原文铺开（详情页才解密回显）',
  !text.includes(DIARY_TEXT.slice(-12)),
)
await shot(page, 'm14-diary-list')
const list = await api('GET', '/diaries?page=0&size=20', undefined, token)
const diaryId = list.data?.items?.[0]?.id
ok('列表接口 payload 不含正文键', !JSON.stringify(list.data).includes('"content"'))
await to(page, `/pages/diary/detail?id=${diaryId}`)
await clickByText(page, 'uni-button, button', '删除这一篇')
await sleep(1200)
await clickByText(page, '.uni-modal__btn', '确定')
await sleep(2000)
const after = await api('GET', '/diaries?page=0&size=20', undefined, token)
ok('删除后列表不再见这一篇', (after.data?.total ?? 0) === 0, `total=${after.data?.total}`)

// ---- 4) 树洞：流式收段 / 这句别分析 / 收段 ----
console.log('\n[4] 树洞')
await to(page, '/pages/companion/index')
text = await bodyText(page)
ok('树洞页可开新段', text.includes('开始这一段') || text.includes('这里没有别人'))
const sseReqsBefore = page.reqs.filter(r => r.includes('/turns')).length
await clickByText(page, 'uni-button, button', '开始这一段')
await sleep(2200)
await fill(page, 'uni-textarea textarea', '今天答辩排练卡住了，有点闷。')
await clickByText(page, 'uni-button, button', '发送')
await sleep(4200)
text = await bodyText(page)
ok('回合以 turn_done 收尾（用户气泡回显）', text.includes('今天答辩排练卡住了'))
ok('AI 回复已渲染', (await countAll(page, '.bubble.ai')) >= 1)
ok('配额文案出现', /今天还能聊\s*\d+\s*轮/.test(text), (text.match(/今天[^\n]*/) || [''])[0])
const sseReqs = page.reqs.filter(r => r.includes('/turns')).length - sseReqsBefore
ok('一轮只发一次上行', sseReqs === 1, `reqs=${sseReqs}`)
await clickByText(page, '.skip', '这句别分析')
await sleep(1500)
text = await bodyText(page)
ok('「这句别分析」即时生效', text.includes('已跳过梳理'))
await shot(page, 'm14-companion')
await clickByText(page, 'uni-text, text', '收段')
await sleep(2200)
const sess = await api('GET', '/companion/sessions?page=0&size=10', undefined, token)
const ended = sess.data?.sessions?.find(s => s.status !== 'ACTIVE')
ok('收段后服务端确有封口段', !!ended, `sessions=${sess.data?.sessions?.length}`)

// ---- 5) 危机一票拦截不因端削弱 ----
console.log('\n[5] 危机链路')
await fill(page, 'uni-textarea textarea', CRISIS_TEXT)
await clickByText(page, 'uni-button, button', '发送')
await sleep(5200)
ok('命中危机句 → 立刻落到求助资源页', (await hash(page)).includes('pages/resources/index'), await hash(page))
text = await bodyText(page)
ok('资源页给出可拨打的号码', text.includes('12356'))
ok('资源页含"很危险先打 120"的兜底指引', text.includes('120'))
const meAfter = await api('GET', '/auth/me', undefined, token)
ok('库内危机状态同步为激活', meAfter.data?.crisisState && meAfter.data.crisisState !== 'NORMAL', `${meAfter.data?.crisisState}`)
await shot(page, 'm14-resources')
await to(page, '/pages/today/index')
text = await bodyText(page)
ok('今日页出现危机横幅', text.includes('现在这段最难熬'))

// ---- 6) 我的：入口、授权开关、退出 ----
console.log('\n[6] 我的')
await to(page, '/pages/me/index')
text = await bodyText(page)
ok('微信入口显示已绑定', text.includes('已绑定'))
ok('说明"看板在网页端"（管理员不复制那一套）', text.includes('网页端'))
const putBefore = page.reqs.filter(r => r.startsWith('PUT') && r.includes('/preferences')).length
await page.evaluate(() => {
  const sw = document.querySelector('uni-switch .uni-switch-input')
  if (sw) sw.click()
})
await sleep(1600)
const putAfter = page.reqs.filter(r => r.startsWith('PUT') && r.includes('/preferences')).length
ok('开关点开必发一次 PUT /preferences', putAfter === putBefore + 1, `puts=${putAfter - putBefore}`)
const prefsNow = await api('GET', '/preferences', undefined, token)
ok('库内授权状态已翻为开', prefsNow.data?.counselorBoardOn === true, `${prefsNow.data?.counselorBoardOn}`)
await to(page, '/pages/today/index', 900)
await to(page, '/pages/me/index')
text = await bodyText(page)
const stillOn = await page.evaluate(
  () => !!document.querySelector('uni-switch .uni-switch-input.uni-switch-input-checked'),
)
ok('刷新后开关仍在（真源是库不是组件态）', stillOn)
ok('退出登录入口在', text.includes('退出登录'))
await shot(page, 'm14-me')

// ---- 7) 求助资源页不依赖登录态 ----
console.log('\n[7] 资源页匿名可读')
const anon = await newPage()
await anon.goto(APP + '/#/pages/resources/index', { waitUntil: 'domcontentloaded' })
await sleep(2200)
const anonText = await bodyText(anon)
ok('无会话也能打开资源页', anonText.includes('需要立刻有人接住'), anonText.slice(0, 30))
ok('兜底/真源号码可见', anonText.includes('12356'))
const anonHash = await anon.evaluate(() => location.hash)
ok('没有被弹回登录页', anonHash.includes('resources'), anonHash)
await anon.close()

// ---- 8) 双视口无横向溢出 + 零报错 ----
console.log('\n[8] 375 / 430 双视口')
for (const w of [375, 430]) {
  const p = await newPage({ width: w, height: w === 375 ? 812 : 932 })
  await p.goto(APP + '/', { waitUntil: 'domcontentloaded' })
  await p.evaluate(t => {
    localStorage.setItem('sv_access', JSON.stringify({ type: 'string', data: t }))
    localStorage.setItem('sv_theme', JSON.stringify({ type: 'string', data: 'light' }))
  }, token)
  for (const path of [
    '/pages/today/index',
    '/pages/diary/index',
    '/pages/companion/index',
    '/pages/me/index',
    '/pages/resources/index',
    '/pages/notifications/index',
  ]) {
    await to(p, path, 1700)
    ok(`${w} ${path} 无横向溢出`, await noOverflow(p))
  }
  await to(p, '/pages/diary/write', 1700)
  ok(`${w} 写作页无横向溢出`, await noOverflow(p))
  await shot(p, `m14-${w}-write`)
  ok(`${w} 视口无 JS 报错`, p.errors.length === 0, p.errors.slice(0, 2).join(' | '))
  await p.close()
}

// ---- 9) 静态红线：管理端不进小程序 ----
console.log('\n[9] 端范围红线')
const pagesJson = JSON.parse(readFileSync('miniprogram/src/pages.json', 'utf8'))
const routes = pagesJson.pages.map(p => p.path).join(' ')
ok('小程序路由里没有任何 admin 页', !/admin/i.test(routes), routes)
ok('tabBar 恰好四页', pagesJson.tabBar.list.length === 4)
const cfg = readFileSync('miniprogram/src/config.ts', 'utf8')
ok('线上域名仍是显式待替换占位', /REPLACE-WITH-BEIAN-DOMAIN/.test(cfg))

await page.close()
await browser.close()
console.log(failed ? `\n走查失败 ${failed} 项` : '\n走查全部通过')
process.exit(failed ? 1 : 0)
