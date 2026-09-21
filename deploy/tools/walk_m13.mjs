// M13 真机走查（无头 Edge，dev 真机 MySQL + Redis，SV_DISTRIBUTED=true）：
//   多节点开关是运维口径，界面只新增一处可观测——管理端首页必须写明"闸门到底跑在哪一侧"。
//   ① /admin 的"推理闸门"一行读出 gate=redis（否则运维只能猜配了没生效）
//   ② 这行不撑破 375/430 窄屏，页面零控制台报错
//   ③ 用户侧主链路（今日页）不受影响：仍能取数、仍不出现任何管理入口
// 用法：后端 :8080 起在 SV_DISTRIBUTED=true、前端 :5173 起好后 `node deploy/tools/walk_m13.mjs`
import { mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const BASE = 'http://localhost:8080/api/v1'
const APP = 'http://localhost:5173'
const SHOTS = 'docs/screenshots'
const PW = 'Passw0rd!2026'

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
  if (json.code !== 0) throw new Error(`${method} ${p} -> ${json.code} ${json.msg}`)
  return json.data
}

const adminTk = (await api('POST', '/auth/login', { username: 'admin', password: PW })).accessToken
const userTk = await (async () => {
  const username = `m13w_${Date.now().toString(36).slice(-6)}`
  const d = await api('POST', '/auth/register', { username, password: PW, nickname: '走查用户' })
  return d.accessToken
})()
ok('种子账号就绪', !!adminTk && !!userTk)

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=430,932'],
})

async function newPage({ token, width = 430, height = 932 }) {
  const page = await browser.newPage()
  await page.setViewport({ width, height, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  page.errors = []
  page.on('pageerror', e => page.errors.push(String(e)))
  page.on('console', m => m.type() === 'error' && page.errors.push('console: ' + m.text()))
  await page.goto(APP + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(t => {
    localStorage.setItem('sv_access', t)
    localStorage.setItem('sv_refresh', '')
    localStorage.setItem('sv_theme', 'light')
  }, token)
  return page
}
const noOverflow = page => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)

// ---- 1) 管理端：闸门跑在哪一侧要看得见 ----
console.log('\n[1] 管理端首页的闸门可观测')
const admin = await newPage({ token: adminTk })
await admin.goto(APP + '/admin', { waitUntil: 'networkidle0' })
await sleep(1500)
const gate = await admin.evaluate(() => {
  const el = [...document.querySelectorAll('p')].find(p => p.textContent.includes('推理闸门'))
  return el ? el.textContent.trim() : null
})
ok('首页有"推理闸门"一行', !!gate, gate || '(缺失)')
ok('SV_DISTRIBUTED=true 时读出 gate=redis', /gate=redis/.test(gate || ''), gate)
ok('同一行报出阈值现值（coins/min 与 inFlight）', /coins\/min=\d+/.test(gate || '') && /inFlight<=\d+/.test(gate || ''))
await admin.evaluate(() => {
  const el = [...document.querySelectorAll('p')].find(p => p.textContent.includes('推理闸门'))
  el?.scrollIntoView({ block: 'center' })
})
await sleep(400)
ok('430 窄屏无横向溢出', await noOverflow(admin))
await admin.screenshot({ path: `${SHOTS}/m13-430-admin-gate.png`, fullPage: false })

await admin.setViewport({ width: 375, height: 812, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
await sleep(600)
await admin.evaluate(() => {
  const el = [...document.querySelectorAll('p')].find(p => p.textContent.includes('推理闸门'))
  el?.scrollIntoView({ block: 'center' })
})
await sleep(300)
ok('375 更窄处也不溢出（闸门文案是长串）', await noOverflow(admin))
await admin.screenshot({ path: `${SHOTS}/m13-375-admin-gate.png`, fullPage: false })
ok('管理端无控制台报错', admin.errors.length === 0, admin.errors.slice(0, 2).join(' | '))
await admin.close()

// ---- 2) 用户侧不受影响 ----
console.log('\n[2] 用户侧主链路')
const user = await newPage({ token: userTk })
await user.goto(APP + '/today', { waitUntil: 'networkidle0' })
await sleep(1200)
const body = await user.evaluate(() => document.body.innerText)
ok('今日页正常取数', /今日|打卡/.test(body))
ok('用户侧看不到闸门运维信息', !/gate=|coins\/min/.test(body))
ok('用户侧没有管理入口', !/管理端|群体看板/.test(body))
await user.goto(APP + '/admin', { waitUntil: 'networkidle0' })
await sleep(800)
ok('普通用户直达 /admin 被弹回登录或今日页',
  ['/login', '/today'].includes(await user.evaluate(() => location.pathname)),
  await user.evaluate(() => location.pathname))
ok('用户侧无控制台报错', user.errors.length === 0, user.errors.slice(0, 2).join(' | '))
await user.close()

await browser.close()
console.log(failed ? `\n结果：${failed} 项失败` : '\n结果：全部通过')
process.exit(failed ? 1 : 0)
