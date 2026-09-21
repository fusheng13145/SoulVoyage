// M12 真机走查：群体看板的界面不变量（无头 Edge，dev 真机 MySQL）
//   ① 用户侧：设置页"群体统计参与"开关默认关、点开必发一次 PUT /preferences、刷新后仍在（真源是库不是组件态）
//   ② 管理端：授权 <10 时看板卡片显示抑制态——没有 KPI、没有曲线，只有结论与红线说明
//   ③ 第 10 人授权后点卡片重拉 → KPI/情绪条/效价条出现，且页面文本里找不到任何成员原文
//   ④ 非管理员直达 /admin/board 必须被路由守卫弹回 /today
//   ⑤ 375/430 双视口无横向溢出
// 用法：后端 :8080（dev profile，已跑 migrate_m12.sql）、前端 :5173 起好后 `node deploy/tools/walk_m12.mjs`
import { mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const BASE = 'http://localhost:8080/api/v1'
const APP = 'http://localhost:5173'
const SHOTS = 'docs/screenshots'
const PW = 'Passw0rd!2026'
const SECRET_NOTE = '走查密文原文绝不该出现在看板'

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

// ---- 1) 种子：12 个新用户，建群体全拉进；b1..b8 与 b10 稍后授权，b9 走界面 ----
const stamp = Date.now().toString(36).slice(-6)
const members = []
for (let i = 1; i <= 12; i++) {
  const username = `m12w_${stamp}_${i}`
  const d = await api('POST', '/auth/register', { username, password: PW, nickname: `走查成员${i}` })
  const me = await api('GET', '/auth/me', undefined, d.accessToken)
  members.push({ username, token: d.accessToken, userId: me.userId })
}
const adminTk = (await api('POST', '/auth/login', { username: 'admin', password: PW })).accessToken
const gname = `走查群体_${stamp}`
const group = await api('POST', '/admin/board/groups', { name: gname }, adminTk)
await api('POST', `/admin/board/groups/${group.id}/members`, { userIds: members.map(m => m.userId) }, adminTk)
for (let i = 0; i < 8; i++) {
  await api('PUT', '/preferences', { counselorBoardOn: true }, members[i].token)
  await api('POST', '/mood-check-ins', { emotion: 'CALM', rating: 4, energy: 3, note: SECRET_NOTE }, members[i].token)
}
console.log('种子:', gname, `#${group.id}`, '成员 12 人 / API 已授权 8 人')

// ---- 浏览器 ----
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=375,812'],
})

async function newPage({ token, width = 375, height = 812 }) {
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
const textOf = page => page.evaluate(() => document.body.innerText)

// ---- 2) 用户侧开关：默认关 → 点开 → 刷新仍在 ----
console.log('\n[2] 设置页授权开关')
const u9 = members[8]
const me = await newPage({ token: u9.token })
await me.goto(APP + '/me', { waitUntil: 'networkidle0' })
await sleep(1200)
const swSel = 'button[aria-label="群体统计参与"]'
const before = await me.evaluate(
  s => {
    const sw = document.querySelector(s)
    const cell = sw?.closest('li,div')
    return { exists: !!sw, checked: sw?.getAttribute('aria-checked'), hint: cell?.textContent || '' }
  },
  swSel,
)
ok('设置页有"群体统计参与"开关', before.exists)
ok('默认关（新账户不授权）', before.checked === 'false')
ok('开关旁写明"不足 10 人…不显示"口径', /10\s*人|不足/.test(before.hint))
let prefPut = 0
me.on('request', r => r.method() === 'PUT' && r.url().includes('/preferences') && prefPut++)
await me.click(swSel)
await sleep(900)
const mid = await me.evaluate(s => document.querySelector(s)?.getAttribute('aria-checked'), swSel)
ok('点一下即上行且回弹为开', mid === 'true' && prefPut === 1, `PUT×${prefPut}`)
await me.reload({ waitUntil: 'networkidle0' })
await sleep(1000)
const after = await me.evaluate(s => document.querySelector(s)?.getAttribute('aria-checked'), swSel)
ok('刷新后仍为开（真源在库不在组件）', after === 'true')
await me.screenshot({ path: `${SHOTS}/m12-375-me-consent.png` })
ok('375 设置页无横向溢出', await noOverflow(me))

// ---- 3) 非管理员直达管理端 → 弹回 ----
console.log('\n[3] 路由守卫')
await me.goto(APP + '/admin/board', { waitUntil: 'networkidle0' })
await sleep(800)
ok('普通用户直达 /admin/board 弹回 /today', (await me.evaluate(() => location.pathname)) === '/today')
await me.close()

// ---- 4) 抑制态：9 人授权（8 API + 1 界面），看板只有结论 ----
console.log('\n[4] 管理端抑制态')
const admin = await newPage({ token: adminTk })
await admin.goto(APP + '/admin/board', { waitUntil: 'networkidle0' })
await sleep(1200)
const card = await admin.evaluate(
  n => {
    const el = [...document.querySelectorAll('.pick')].find(c => c.textContent.includes(n))
    return el ? { pill: el.querySelector('.pill')?.textContent?.trim(), meta: el.querySelector('.meta')?.textContent?.trim() } : null
  },
  gname,
)
ok('群体卡片可见且标注抑制', card && /抑制/.test(card.pill || ''), JSON.stringify(card))
ok('卡片写明 9/10 授权口径', /9\s*\/\s*10/.test(card?.meta || ''), card?.meta)
await admin.evaluate(
  n => {
    const el = [...document.querySelectorAll('.pick')].find(c => c.textContent.includes(n))
    el?.click()
  },
  gname,
)
await sleep(1500)
const sup = await admin.evaluate(() => ({
  notice: document.querySelector('.notice')?.textContent?.replace(/\s+/g, ' ') || '',
  kpis: document.querySelectorAll('.kpi').length,
  bars: document.querySelectorAll('.bar-row').length,
}))
ok('抑制态展示原因（防个体反推）', /防个体反推/.test(sup.notice))
ok('抑制态没有任何 KPI 与曲线', sup.kpis === 0 && sup.bars === 0, `kpi=${sup.kpis} bar=${sup.bars}`)
await admin.screenshot({ path: `${SHOTS}/m12-375-board-suppressed.png` })

// ---- 5) 解锁：第 10 人授权 + 打卡后重拉，聚合出现且无个体痕迹 ----
console.log('\n[5] 解锁聚合态')
await api('PUT', '/preferences', { counselorBoardOn: true }, members[9].token)
await api('POST', '/mood-check-ins', { emotion: 'CALM', rating: 4, energy: 3, note: SECRET_NOTE }, members[9].token)
await api('POST', '/mood-check-ins', { emotion: 'JOY', rating: 5, energy: 4, note: SECRET_NOTE }, members[8].token) // b9 在界面上已授权，补上他的打卡
await admin.reload({ waitUntil: 'networkidle0' })
await sleep(1200)
await admin.evaluate(
  n => {
    const el = [...document.querySelectorAll('.pick')].find(c => c.textContent.includes(n))
    el?.click()
  },
  gname,
)
await sleep(1800)
const act = await admin.evaluate(() => ({
  pill: document.querySelector('.pick.on .pill')?.textContent?.trim(),
  kpis: [...document.querySelectorAll('.kpi')].map(k => k.textContent.replace(/\s+/g, ' ').trim()),
  bars: document.querySelectorAll('.bar-row').length,
  emotion: [...document.querySelectorAll('.bar-label')].map(x => x.textContent.trim()).join(','),
  body: document.body.innerText,
}))
ok('列表徽标转为可看聚合', /聚合|可看/.test(act.pill || ''), act.pill)
ok('KPI 出现且参与人数=10', act.kpis.some(k => /10/.test(k) && /参与/.test(k)), act.kpis[0])
ok('情绪分布用中文标签（平静在场）', /平静/.test(act.emotion), act.emotion)
ok('效价/情绪条形已渲染', act.bars >= 2, 'bar-row=' + act.bars)
ok('聚合页不含打卡原文', !act.body.includes(SECRET_NOTE))
ok('聚合区不含"走查密文"字样（正文零泄露）', !/走查密文/.test(act.body))
await admin.screenshot({ path: `${SHOTS}/m12-375-board-stats.png` })
ok('375 看板无横向溢出', await noOverflow(admin))

// ---- 6) 成员维护界面路径：检索候选 → 移出（确认框）→ 重新抑制 ----
console.log('\n[6] 成员移出即回落')
await admin.evaluate(u => {
  const i = document.querySelector('input[placeholder*="检索"]')
  i.value = u
  i.dispatchEvent(new Event('input', { bubbles: true }))
}, members[9].username)
await admin.evaluate(() => [...document.querySelectorAll('.row .btn')].find(b => b.textContent.trim() === '搜')?.click())
await sleep(1200)
const cand = await admin.evaluate(() => !!document.querySelector('.cand'))
ok('按用户名可检索到候选', cand)
await admin.evaluate(() => [...document.querySelectorAll('.linklike')].find(b => b.textContent.includes('移出'))?.click())
await sleep(600)
const dlg = await admin.evaluate(() => document.body.innerText.includes('移出'))
ok('移出前弹确认框（危险操作不即点即删）', dlg)
await admin.evaluate(() =>
  [...document.querySelectorAll('[role="alertdialog"] .btns button.ok')].find(b => b.textContent.includes('移出'))?.click(),
)
await sleep(1800)
const back = await admin.evaluate(() => ({
  notice: !!document.querySelector('.notice'),
  kpis: document.querySelectorAll('.kpi').length,
}))
ok('移出授权成员后看板回到抑制', back.notice && back.kpis === 0)
await admin.screenshot({ path: `${SHOTS}/m12-375-board-suppressed-again.png` })

// ---- 7) 430 视口 + 控制台零错误 ----
console.log('\n[7] 430 视口与错误面')
const wide = await newPage({ token: adminTk, width: 430, height: 932 })
await wide.goto(APP + '/admin/board', { waitUntil: 'networkidle0' })
await sleep(1200)
ok('430 看板无横向溢出', await noOverflow(wide))
await wide.evaluate(
  n => {
    const el = [...document.querySelectorAll('.pick')].find(c => c.textContent.includes(n))
    el?.click()
  },
  gname,
)
await sleep(1500)
const allText = await textOf(wide)
ok('430 下抑制说明完整可读', /防个体反推/.test(allText))
await wide.screenshot({ path: `${SHOTS}/m12-430-board.png` })
const errs = [...admin.errors, ...wide.errors]
ok('管理端全程无页面/控制台错误', errs.length === 0, errs.slice(0, 2).join(' | '))

await browser.close()
console.log(`\n${failed ? failed + ' FAILED' : 'ALL PASS'}`)
process.exit(failed ? 1 : 0)
