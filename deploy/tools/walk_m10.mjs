// M10 上线门禁走查：375×812 与 430×932 两端窄屏全页面（此前固定 430×932 单一视口）
//   ① 横向溢出  ② 可点件被遮挡（只判真正落在滚动容器可视区内的件）  ③ 键盘弹出（视口压到 500 高）后输入框仍在可视区内
//   ④ 安全区 token 覆盖面（env() 定义 + var(--sv-safe-*) 消费点 + viewport-fit=cover）  ⑤ 触控目标尺寸（信息项）
// 用法：后端 :8080、前端 :5173 起好后 `node deploy/tools/walk_m10.mjs`
// 说明：无头浏览器弹不出系统软键盘，故按 iOS 键盘实际占用高度把视口从 812 压到 500 复现——
//       布局若靠 100dvh + 文档流内输入区撑住，两种口径等价；真机键盘仍需人工过一遍（门禁已注明）。
import { mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const BASE = 'http://localhost:8080/api/v1'
const APP = 'http://localhost:5173'
const SHOTS = 'docs/screenshots'
const PW = 'Passw0rd!2026'
const W = 375
const H = 812
const KB_H = 500

mkdirSync(SHOTS, { recursive: true })
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function api(method, p, body, token) {
  const res = await fetch(BASE + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== 0) throw new Error(`${method} ${p} -> ${json.code} ${json.msg}`)
  return json.data
}

// ---- 1) 造一个"有内容"的账号：每页都不该是空态 ----
const user = 'm10_ui_' + Date.now().toString(36).slice(-6)
const { accessToken: token } = await api('POST', '/auth/register', {
  username: user,
  password: PW,
  nickname: '窄屏走查',
})
const diaryText = '组会汇报被打断三次，回宿舍一路上都在想刚才那句话该怎么接，越想越闷。'
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: { diaryText, recordDate: new Date().toISOString().slice(0, 10), moodSelfRating: 2 },
  clientReqId: 'm10walk-' + Date.now(),
}, token)
for (let i = 0; i < 90; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, token)
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break
  await sleep(2000)
}
const sid = (await api('POST', '/companion/sessions', {}, token)).sessionId
await fetch(BASE + `/companion/sessions/${sid}/turns`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ userText: '今天还是有点闷，想说两句。' }),
}).then(r => r.text())
const diaries = await api('GET', '/diaries', undefined, token)
const reports = await api('GET', '/reports', undefined, token)
const diaryId = diaries.items[0]?.id
const reportId = reports.items[0]?.id
if (!diaryId || !reportId) throw new Error('种子数据不足：走查需要日记与报告各一条')
console.log('种子就绪:', user, 'diary=', diaryId, 'report=', reportId)

// ---- 2) 无头 Edge @375 ----
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', `--window-size=${W},${H}`],
})
const page = await browser.newPage()
await page.setViewport({ width: W, height: H, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
const pageErrors = []
page.on('pageerror', e => pageErrors.push(String(e)))

async function open(route, settle = 1400) {
  await page.goto(APP + route, { waitUntil: 'networkidle0' })
  await sleep(settle)
}
async function login() {
  await page.goto(APP + '/login')
  await page.evaluate(t => {
    localStorage.setItem('sv_access', t)
    localStorage.setItem('sv_refresh', '')
    localStorage.setItem('sv_theme', 'light')
  }, token)
}

const describe = el =>
  el.tagName.toLowerCase() +
  (typeof el.className === 'string' && el.className.trim()
    ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.')
    : '')

/** 页面体检：横向溢出 + 可点件遮挡 + 触控目标（滚动前后各测一次） */
async function audit() {
  return page.evaluate(() => {
    const vw = window.innerWidth
    const de = document.documentElement
    const main = document.querySelector('main.screen')
    const desc = el =>
      el.tagName.toLowerCase() +
      (typeof el.className === 'string' && el.className.trim()
        ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.')
        : '')
    const offenders = []
    if (de.scrollWidth > vw + 1) {
      for (const el of document.querySelectorAll('body *')) {
        const r = el.getBoundingClientRect()
        if (r.width > vw + 1 || r.right > vw + 1) offenders.push(desc(el) + '@' + Math.round(r.right))
      }
    }
    // 被遮挡判据：只测"完整落在所有滚动容器可视区内"的可点件——
    // 被滚动容器裁到一半的件本来就在屏外，命中它下方的是 tabbar 而非浮层，不算遮挡
    const blocked = []
    const clipRect = el => {
      const r = el.getBoundingClientRect()
      let box = { left: r.left, top: r.top, right: r.right, bottom: r.bottom }
      for (let a = el.parentElement; a; a = a.parentElement) {
        const cs = getComputedStyle(a)
        if (cs.overflowY === 'visible' && cs.overflowX === 'visible') continue
        const ar = a.getBoundingClientRect()
        box.left = Math.max(box.left, ar.left)
        box.top = Math.max(box.top, ar.top)
        box.right = Math.min(box.right, ar.right)
        box.bottom = Math.min(box.bottom, ar.bottom)
      }
      return box
    }
    for (const el of document.querySelectorAll('button,a[href],input,textarea,select,[role="button"]')) {
      const r = el.getBoundingClientRect()
      if (!r.width || !r.height) continue
      if (r.top < 0 || r.bottom > window.innerHeight) continue
      const box = clipRect(el)
      const cx = r.left + r.width / 2
      const cy = r.top + r.height / 2
      if (cx < box.left || cx > box.right || cy < box.top || cy > box.bottom) continue
      const hit = document.elementFromPoint(cx, cy)
      if (hit && hit !== el && !el.contains(hit) && !hit.contains(el))
        blocked.push(desc(el) + ' 被 ' + desc(hit) + ' 盖住')
    }
    const small = []
    for (const el of document.querySelectorAll('button,a[href],[role="button"]')) {
      const r = el.getBoundingClientRect()
      if (r.width && (r.height < 44 || r.width < 44)) small.push(desc(el) + ' ' + Math.round(r.width) + '×' + Math.round(r.height))
    }
    const bar = document.querySelector('nav.tabbar')
    return {
      overflow: de.scrollWidth - vw,
      offenders: offenders.slice(0, 4),
      blocked: blocked.slice(0, 4),
      small: small.slice(0, 5),
      tabbar: bar ? Math.round(parseFloat(getComputedStyle(bar).paddingBottom)) : null,
      scrolled: main ? Math.round(main.scrollTop) + '/' + Math.round(main.scrollHeight) : null,
    }
  })
}

const rows = []
let failed = 0
const PAGES = [
  ['/login', 'login'],
  ['/today', 'today'],
  ['/diaries', 'diaries'],
  ['/practice', 'practice'],
  ['/insights', 'insights'],
  ['/me', 'me'],
  ['/diaries/write', 'diary-write'],
  [`/diaries/${diaryId}`, 'diary-detail'],
  [`/diaries/analysis?task=${taskNo}`, 'analysis'],
  ['/companion', 'companion'],
  ['/practice/sim', 'sim'],
  ['/practice/room/ex_54321', 'practice-room'],
  ['/archive', 'archive'],
  [`/archive/report/${reportId}`, 'report-detail'],
  ['/letters', 'letters'],
  ['/achievements', 'achievements'],
  ['/notifications', 'notifications'],
  ['/account', 'account'],
  ['/crisis', 'crisis'],
]

await login()
// 门禁口径是 375–430 一整段：窄端 375 出截图，宽端 430（此前各里程碑的固定基线）只复核判据
for (const width of [W, 430]) {
  await page.setViewport({
    width,
    height: width === W ? H : 932,
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })
  console.log(`\n===== ${width}px 全页面走查 =====`)
  for (const [route, name] of PAGES) {
    await open(route)
    const top = await audit()
    await page.evaluate(() => {
      const m = document.querySelector('main.screen')
      if (m) m.scrollTop = m.scrollHeight
    })
    await sleep(250)
    const bottom = await audit()
    if (width === W) await page.screenshot({ path: `${SHOTS}/m10-375-${name}.png` })
    const bad =
      top.overflow > 1 || bottom.overflow > 1 || top.blocked.length || bottom.blocked.length
    if (bad) failed++
    console.log(
      `${bad ? '✗' : '✓'} ${name.padEnd(14)} 溢出 ${Math.max(top.overflow, bottom.overflow)}px` +
        ` | 遮挡 ${[...new Set([...top.blocked, ...bottom.blocked])].join('、') || '无'}` +
        ` | 小目标 ${[...new Set([...top.small, ...bottom.small])].slice(0, 3).join('、') || '无'}`,
    )
    if (top.offenders.length || bottom.offenders.length)
      console.log('    撑宽元素:', [...new Set([...top.offenders, ...bottom.offenders])].join('、'))
    rows.push({ width, name, route, top, bottom })
  }
}

// ---- 3) 键盘避让：视口压到 500 高，聚焦输入件后必须仍在可视区内 ----
console.log(`\n===== 键盘视口压缩 ${W}×${KB_H} =====`)
await page.setViewport({ width: W, height: KB_H, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
const INPUTS = [
  { route: '/companion', sel: 'textarea' },
  { route: '/diaries/write', sel: 'textarea' },
  // 训练输入件在"进入场景"之后才挂载，先点一次主按钮推进到对话态
  { route: '/practice/sim', sel: 'textarea', via: '进入场景' },
  { route: '/diaries', sel: 'input' },
]
for (const { route, sel, via } of INPUTS) {
  await open(route)
  if (via) {
    const clicked = await page.evaluate(t => {
      const el = [...document.querySelectorAll('button')].find(b => (b.textContent || '').includes(t))
      if (el) el.click()
      return !!el
    }, via)
    if (clicked) await sleep(1800)
  }
  const found = await page.evaluate(s => {
    const el = document.querySelector(s)
    if (!el) return false
    el.scrollIntoView({ block: 'center' })
    el.focus()
    return true
  }, sel)
  if (!found) {
    console.log(`· ${route} 无 ${sel}，跳过`)
    continue
  }
  await page.keyboard.type('键盘弹出时这句话要被完整看见')
  await sleep(400)
  const kb = await page.evaluate(() => {
    const a = document.activeElement
    const r = a.getBoundingClientRect()
    return {
      vh: window.innerHeight,
      top: Math.round(r.top),
      bottom: Math.round(r.bottom),
      visible: r.top >= 0 && r.bottom <= window.innerHeight + 1,
    }
  })
  if (!kb.visible) failed++
  console.log(
    `${kb.visible ? '✓' : '✗'} ${route.padEnd(16)} 视口 ${kb.vh} 输入件 ${kb.top}~${kb.bottom}` +
      ` ${kb.visible ? '完整可见' : '被键盘切掉'}`,
  )
  await page.screenshot({ path: `${SHOTS}/m10-kb-${route.replace(/[^a-z]/gi, '')}.png` })
}

// ---- 4) 安全区：token 定义 + 消费点覆盖面（结构件靠 var(--sv-safe-*)，不直接写 env()）----
await page.setViewport({ width: W, height: H, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
await open('/today')
const safe = await page.evaluate(() => {
  const defs = []
  const consumers = []
  for (const sheet of document.styleSheets) {
    let rules
    try {
      rules = sheet.cssRules
    } catch {
      continue
    }
    for (const r of rules) {
      if (!r.cssText || !r.selectorText) continue
      if (r.cssText.includes('safe-area-inset')) defs.push(r.selectorText)
      if (r.cssText.includes('var(--sv-safe')) consumers.push(r.selectorText)
    }
  }
  return {
    defs: [...new Set(defs)],
    consumers: [...new Set(consumers)],
    viewportFit: /viewport-fit=cover/.test(document.querySelector('meta[name=viewport]').content),
  }
})
console.log('\n===== 安全区 =====')
console.log(`viewport-fit=cover: ${safe.viewportFit ? '✓' : '✗ 未声明'}`)
console.log(`env() 定义于: ${safe.defs.join('、') || '（无）'}`)
console.log(`消费点 ${safe.consumers.length} 处: ${safe.consumers.join('、')}`)
if (!safe.viewportFit || !safe.defs.length || safe.consumers.length < 4) failed++

await browser.close()
if (pageErrors.length) {
  failed++
  console.log('\n⚠ 页面报错:', pageErrors)
}
console.log(`\n375px 走查完成：${PAGES.length} 页 + ${INPUTS.length} 项键盘避让，${failed ? failed + ' 项不通过 ❌' : '全部通过 ✅'}`)
if (failed) process.exitCode = 1
