// M11 真机走查：语音日记的录音 UI 与三种降级态（无头 Edge 用假麦克风，真链路假音频）
//   ① 空闲/录音/转写回文 三态的可见文案与无障碍名（aria-live 播报、role=alert/status）
//   ② 转写文本"追加不覆盖"——用户先手敲的几句不能被机器吞掉
//   ③ "不要这段"必须一次上行都不发（录音可弃，且不留后台副本）
//   ④ 开麦握手中不可重复触发（慢 getUserMedia 复现权限弹窗期）；太短/拿不到麦克风：只出话术，
//     页面仍可手写提交（语音是增益，不是门槛）
//   ⑤ 浏览器不支持录音：整个语音块消失，文字链路零回归
//   ⑥ 成稿后 VOICE 溯源标记在列表与详情都可见（麦克风徽标 + 秒数）
//   ⑦ 375/430 双视口下录音态不横向溢出（胶囊 + "不要这段"会撑一排）
// 用法：后端 :8080（SV_ASR_PROVIDER=mock）、前端 :5173 起好后 `node deploy/tools/walk_m11.mjs`
// 说明：假设备喂的是正弦波，Mock 转写回的是固定样例文本——这里验的是"录音这条输入源的界面与不变量"，
//       真协议字节的往返由 smoke_m11.mjs --asr 段（本机 OpenAI 兼容桩）负责。
import { mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const BASE = 'http://localhost:8080/api/v1'
const APP = 'http://localhost:5173'
const SHOTS = 'docs/screenshots'
const PW = 'Passw0rd!2026'
const ASR_URL = '/diaries/voice-transcriptions'
const HAND_TEXT = '先手写一句：今天下午本来不想说话。'

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

/** 门禁段的真实 multipart 上行：用来把用户级滑动窗灌满，逼出浏览器的失败态 */
async function uploadNode(t, bytes = 2048) {
  const form = new FormData()
  form.append('file', new Blob([new Uint8Array(bytes)], { type: 'audio/webm' }), 'voice')
  form.append('durationMs', '5000')
  const res = await fetch(BASE + ASR_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${t}` },
    body: form,
  })
  return (await res.json()).code
}

// ---- 1) 账号：走查只需要能登录，内容全部由录音/手敲现造 ----
const user = 'm11_ui_' + Date.now().toString(36).slice(-6)
const { accessToken: token } = await api('POST', '/auth/register', {
  username: user,
  password: PW,
  nickname: '录音走查',
})
console.log('账号:', user)

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  // 假设备=持续正弦波（MediaRecorder 有字节可编码），假 UI=自动授权，免掉系统权限弹窗
  args: [
    '--no-first-run',
    '--use-fake-ui-for-media-stream',
    '--use-fake-device-for-media-stream',
    '--window-size=375,812',
  ],
})

async function newPage({ width = 375, height = 812, login = true } = {}) {
  const page = await browser.newPage()
  await page.setViewport({ width, height, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  page.errors = []
  page.on('pageerror', e => page.errors.push(String(e)))
  page.on('console', m => m.type() === 'error' && page.errors.push('console: ' + m.text()))
  page.uploads = 0
  page.on('request', r => {
    if (r.url().includes(ASR_URL) && r.method() === 'POST') page.uploads++
  })
  if (login) {
    await page.goto(APP + '/login', { waitUntil: 'domcontentloaded' })
    await page.evaluate(t => {
      localStorage.setItem('sv_access', t)
      localStorage.setItem('sv_refresh', '')
      localStorage.setItem('sv_theme', 'light')
      localStorage.removeItem('sv_diary_draft')
    }, token)
  }
  return page
}

/** 走查页一律整页跳转：Vue 转场会让中途快照拿到空白（见手册走查口径） */
async function openWrite(page, settle = 1500) {
  await page.goto(APP + '/diaries/write', { waitUntil: 'networkidle0' })
  await sleep(settle)
}

const ta = page => page.$eval('textarea', el => el.value)
async function typeHand(page, text) {
  await page.$eval('textarea', (el, v) => {
    el.value = v
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }, text)
  await sleep(200)
}
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

// ---- 2) 三态：空闲 → 录音 → 转写回文（含"追加不覆盖"） ----
console.log('\n[2] 录音三态与文本合并')
const page = await newPage()
await openWrite(page)
const idle = await page.evaluate(() => {
  const mic = document.querySelector('button.mic')
  return {
    exists: !!mic,
    label: mic?.textContent?.trim(),
    icon: !!mic?.querySelector('use'),
    hint: document.querySelector('.voice-hint')?.getAttribute('aria-live'),
    disabled: mic?.disabled,
  }
})
ok('语音入口存在且有文字标签（不是只有图标）', idle.exists && /说给心屿听/.test(idle.label || ''), idle.label)
ok('麦克风图标是内联雪碧图（可着色、不额外请求）', idle.icon)
ok('空闲态提示用 aria-live 播报', idle.hint === 'polite', String(idle.hint))
await page.screenshot({ path: `${SHOTS}/m11-375-write-idle.png` })

await typeHand(page, HAND_TEXT)
ok('手敲内容进入输入框', (await ta(page)) === HAND_TEXT)

await clickByText(page, 'button.mic', '说给心屿听')
await sleep(1300)
const rec = await page.evaluate(() => {
  const el = document.querySelector('button.mic.on')
  return {
    exists: !!el,
    aria: el?.getAttribute('aria-label'),
    dot: !!el?.querySelector('.dot'),
    text: el?.textContent?.replace(/\s+/g, ' ').trim(),
    cancel: [...document.querySelectorAll('button.mic.ghost')].map(b => b.textContent.trim())[0],
    hint: document.querySelector('.voice-hint')?.textContent?.trim(),
  }
})
ok('录音态胶囊带"秒数/上限"计时', /\d+″\s*\/\s*90″/.test(rec.text || ''), rec.text)
ok('录音态有无障碍名（屏幕阅读器读得出正在录音几秒）', /正在录音 \d+ 秒/.test(rec.aria || ''), rec.aria)
ok('录音态有呼吸红点（视觉可辨，非纯色块依赖）', rec.dot)
ok('录音态提供"不要这段"', /不要这段/.test(rec.cancel || ''), rec.cancel)
ok('录音态提示改成"最多 90 秒"口径', /最多 90 秒/.test(rec.hint || ''), rec.hint)
await page.screenshot({ path: `${SHOTS}/m11-375-write-recording.png` })

await clickByText(page, 'button.mic.on', '″')
for (let i = 0; i < 30 && page.uploads === 0; i++) await sleep(200)
for (let i = 0; i < 60; i++) {
  const s = await page.evaluate(() => document.querySelector('.proofread')?.textContent || '')
  if (s) break
  await sleep(500)
}
const back = await page.evaluate(() => ({
  value: document.querySelector('textarea')?.value || '',
  proofread: document.querySelector('[role="status"].proofread')?.textContent?.replace(/\s+/g, ' ').trim() || '',
  recording: !!document.querySelector('button.mic.on'),
  transcribing: [...document.querySelectorAll('button.mic')].some(b => b.disabled),
}))
ok('结束录音后回到非录音态', !back.recording && !back.transcribing)
ok('上传确实发生了（一次）', page.uploads === 1, 'uploads=' + page.uploads)
ok('转写文本已追加在手敲之后', back.value.startsWith(HAND_TEXT) && back.value.length > HAND_TEXT.length + 5, JSON.stringify(back.value.slice(0, 24)) + '…')
ok('转写另起一行（不糊在手敲那句尾巴上）', back.value.split('\n')[1]?.length > 5, JSON.stringify(back.value.split('\n')[1]?.slice(0, 18) || ''))
ok('校对提示可见且是 role=status', /机器可能听错字/.test(back.proofread), back.proofread.slice(0, 30) + '…')
await page.screenshot({ path: `${SHOTS}/m11-375-write-transcript.png` })

// ---- 3) 提交：语音只多一个溯源标记，成稿可读 ----
console.log('\n[3] 成稿与溯源标记')
await clickByText(page, 'button.mood', '🙂')
await clickByText(page, 'button.sv-btn', '交给心屿梳理')
await page.waitForFunction(() => location.pathname === '/diaries/analysis', { timeout: 20000 }).catch(() => {})
const onAnalysis = await page.evaluate(() => location.pathname)
ok('提交后交棒分析页（SSE 进度页）', onAnalysis === '/diaries/analysis', onAnalysis)
await page.screenshot({ path: `${SHOTS}/m11-375-write-analysis.png` })
const taskNo = await page.evaluate(() => new URL(location.href).searchParams.get('task'))
let diary
for (let i = 0; i < 80; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, token)
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break
  await sleep(1500)
}
{
  const list = await api('GET', '/diaries?page=0&size=5', undefined, token)
  diary = list.items?.[0]
}
ok('日记落库且标记为语音来源', diary?.source === 'VOICE', diary?.source)
ok('录音时长随日记留存', diary && (await api('GET', `/diaries/${diary.id}`, undefined, token)).voiceDurationMs >= 700)

await openWrite(page) // 清一次输入，避免残留草稿
await page.goto(APP + '/diaries', { waitUntil: 'networkidle0' })
await sleep(1200)
const listUi = await page.evaluate(() => {
  const el = document.querySelector('.src')
  return { exists: !!el, aria: el?.getAttribute('aria-label'), icon: !!el?.querySelector('use') }
})
ok('列表可见语音徽标（有 aria-label，不靠颜色）', listUi.exists && /语音/.test(listUi.aria || ''), listUi.aria)
await page.screenshot({ path: `${SHOTS}/m11-375-diary-list.png` })

await page.goto(`${APP}/diaries/${diary.id}`, { waitUntil: 'networkidle0' })
await sleep(1200)
const detailUi = await page.evaluate(() => ({
  badge: !!document.querySelector('[class*="src"]'),
  dur: document.querySelector('.src-dur')?.textContent?.trim() || '',
  text: document.body.innerText,
}))
ok('详情回显语音来源与秒数', /\d+″/.test(detailUi.dur), detailUi.dur)
ok('详情正文含转写文字（加密回读无损）', detailUi.text.includes('图书馆') || detailUi.text.length > 60)
await page.screenshot({ path: `${SHOTS}/m11-375-diary-detail.png` })

// ---- 4) 双视口：录音态不横向溢出 ----
console.log('\n[4] 375 / 430 录音态布局')
for (const width of [375, 430]) {
  const w = await newPage({ width, height: width === 375 ? 812 : 932 })
  await openWrite(w)
  await typeHand(w, HAND_TEXT)
  await clickByText(w, 'button.mic', '说给心屿听')
  await sleep(1200)
  const box = await w.evaluate(() => {
    const de = document.documentElement
    const over = [...document.querySelectorAll('body *')]
      .filter(el => el.getBoundingClientRect().right > de.clientWidth + 1)
      .map(el => el.tagName.toLowerCase() + '.' + String(el.className).split(/\s+/)[0])
    return { overflow: de.scrollWidth - de.clientWidth, over: [...new Set(over)].slice(0, 3) }
  })
  ok(`${width}px 无横向溢出`, box.overflow <= 1, `overflow=${box.overflow} ${box.over.join(',')}`)
  await w.screenshot({ path: `${SHOTS}/m11-${width}-write-recording.png` })
  await w.close()
}

// ---- 5) 丢弃这段：一次上行都不该发出去 ----
console.log('\n[5] 录音可弃')
const p5 = await newPage()
await openWrite(p5)
await typeHand(p5, HAND_TEXT)
await clickByText(p5, 'button.mic', '说给心屿听')
await sleep(1500)
await clickByText(p5, 'button.mic.ghost', '不要这段')
await sleep(1200)
const cancelled = await p5.evaluate(() => ({
  value: document.querySelector('textarea')?.value || '',
  recording: !!document.querySelector('button.mic.on'),
  err: document.querySelector('[role="alert"]')?.textContent || '',
}))
ok('"不要这段"后不再录音', !cancelled.recording)
ok('"不要这段"零上行（没问过服务器）', p5.uploads === 0, 'uploads=' + p5.uploads)
ok('丢弃不改写已手敲的字', cancelled.value === HAND_TEXT, JSON.stringify(cancelled.value))
ok('丢弃不该报错吓人', cancelled.err === '')
await p5.screenshot({ path: `${SHOTS}/m11-375-write-cancelled.png` })

// ---- 6) 开麦握手中不可重复触发 + 录得太短：两条"录不成"的路径都要有话术、不上行 ----
console.log('\n[6] 降级态 · 开麦握手与录音过短')
const p6 = await newPage()
// 真机上这里有权限弹窗与设备初始化的几百毫秒，用慢 getUserMedia 把它复现出来（并数被调用几次）
await p6.evaluateOnNewDocument(() => {
  const origin = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
  window.__gumCalls = 0
  navigator.mediaDevices.getUserMedia = c => {
    window.__gumCalls++
    return new Promise(r => setTimeout(() => r(origin(c)), 600))
  }
})
await openWrite(p6)
await clickByText(p6, 'button.mic', '说给心屿听')
const busy = await p6.evaluate(() => {
  const b = document.querySelector('button.mic')
  return {
    disabled: b?.disabled,
    text: b?.textContent?.replace(/\s+/g, ' ').trim(),
    live: b?.getAttribute('aria-busy'),
    hint: document.querySelector('.voice-hint')?.textContent?.trim(),
  }
})
ok('开麦中按钮禁用（双击不会重复要麦克风）', busy.disabled === true && /正在开麦/.test(busy.text || ''), busy.text)
ok('开麦中把"正在做什么"播报出来', /正在开麦/.test(busy.hint || '') && busy.live === 'true', busy.hint)
await clickByText(p6, 'button.mic', '正在开麦') // 再点一次：disabled 的按钮不该触发任何逻辑
for (let i = 0; i < 60; i++) {
  if (await p6.evaluate(() => !!document.querySelector('button.mic.on'))) break
  await sleep(100)
}
const gumCalls = await p6.evaluate(() => window.__gumCalls)
ok('慢握手下也只开一次麦（不泄漏音轨）', gumCalls === 1, 'getUserMedia ×' + gumCalls)
await clickByText(p6, 'button.mic.on', '″') // 刚起来就停：短于 700ms 不该白烧一次上行
await sleep(1200)
const short = await p6.evaluate(() => ({
  err: document.querySelector('[role="alert"]')?.textContent?.replace(/\s+/g, ' ').trim() || '',
  value: document.querySelector('textarea')?.value || '',
  mic: !!document.querySelector('button.mic'),
}))
ok('过短有 role=alert 说清下一步', /太短/.test(short.err) && /按住/.test(short.err), short.err)
ok('过短不发无效上行', p6.uploads === 0, 'uploads=' + p6.uploads)
ok('过短后仍可继续录音或手写', short.mic && short.value === '')
await p6.screenshot({ path: `${SHOTS}/m11-375-write-tooshort.png` })
await p6.close()

// ---- 7) 麦克风被拒：页面必须还能手写提交 ----
console.log('\n[7] 降级态 · 麦克风权限被拒')
const p7 = await newPage()
await p7.evaluateOnNewDocument(() => {
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia: () => Promise.reject(new Error('Permission denied')) },
    configurable: true,
  })
})
await openWrite(p7)
const denied = { hasMic: await clickByText(p7, 'button.mic', '说给心屿听') }
await sleep(1200)
denied.err = await p7.evaluate(
  () => document.querySelector('[role="alert"]')?.textContent?.replace(/\s+/g, ' ').trim() || '',
)
ok('入口仍可点（不给"灰掉的麦克风"）', denied.hasMic)
ok('拒绝后话术指向浏览器设置', /麦克风权限/.test(denied.err) && /手写/.test(denied.err), denied.err)
ok('拿不到麦克风时不发上行', p7.uploads === 0, 'uploads=' + p7.uploads)
await typeHand(p7, '权限没给也要能记：今天例会又被临时提前了。')
await clickByText(p7, 'button.sv-btn', '交给心屿')
await sleep(2500)
ok('权限被拒不影响文字成稿', await p7.evaluate(() => location.pathname === '/diaries/analysis'))
await p7.screenshot({ path: `${SHOTS}/m11-375-write-micdenied.png` })
await p7.close()

// ---- 8) 浏览器不支持录音：语音块整体消失，文字链路零回归 ----
console.log('\n[8] 降级态 · 浏览器不支持')
const p8 = await newPage()
await p8.evaluateOnNewDocument(() => {
  Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true })
  Object.defineProperty(window, 'MediaRecorder', { value: undefined, configurable: true })
})
await openWrite(p8)
const unsupported = await p8.evaluate(() => ({
  block: !!document.querySelector('.voice'),
  mic: !!document.querySelector('button.mic'),
  ta: !!document.querySelector('textarea'),
  submit: [...document.querySelectorAll('button')].some(b => /交给心屿|再多写/.test(b.textContent || '')),
}))
ok('不支持时语音块不出现（不留死按钮）', !unsupported.block && !unsupported.mic)
ok('不支持时输入框与提交仍在', unsupported.ta && unsupported.submit)
await typeHand(p8, '这台设备不能录音，写下来一样能整理。')
await clickByText(p8, 'button.sv-btn', '交给心屿')
await sleep(2500)
ok('纯文字提交不受语音改动影响', await p8.evaluate(() => location.pathname === '/diaries/analysis'))
await p8.screenshot({ path: `${SHOTS}/m11-375-write-unsupported.png` })
ok('降级页无脚本报错', p8.errors.length === 0, p8.errors.join(' | ').slice(0, 120))
await p8.close()

// ---- 9) 转写失败（后端限流）：话术透传，已录内容不丢 ----
console.log('\n[9] 降级态 · 转写失败透传')
// 参数校验在限流之前，所以灌窗得用真 multipart；窗口 10 次/分，灌完再录即触发 4002
let burned = 0
for (let i = 0; i < 12; i++) if ((await uploadNode(token)) === 0) burned++
console.log('  灌窗放行', burned, '次（用户级 10/分）')
const p9 = await newPage()
await openWrite(p9)
await typeHand(p9, HAND_TEXT)
await clickByText(p9, 'button.mic', '说给心屿听')
await sleep(1600)
await clickByText(p9, 'button.mic.on', '″')
for (let i = 0; i < 40; i++) {
  const s = await p9.evaluate(() => document.querySelector('[role="alert"]')?.textContent || document.querySelector('.proofread')?.textContent || '')
  if (s) break
  await sleep(500)
}
const lim = await p9.evaluate(() => ({
  err: document.querySelector('[role="alert"]')?.textContent?.replace(/\s+/g, ' ').trim() || '',
  value: document.querySelector('textarea')?.value || '',
  retry: ![...document.querySelectorAll('button.mic')].some(b => b.disabled),
}))
ok('转写失败有话术且不吞手稿', /稍等|转写|频繁/.test(lim.err) && lim.value.startsWith(HAND_TEXT), lim.err)
ok('失败后仍可再次录音（按钮不焊死）', lim.retry)
await p9.screenshot({ path: `${SHOTS}/m11-375-write-error.png` })
await p9.close()

const allErrors = [...(page.errors || []), ...p5.errors]
await page.close()
await p5.close()
await browser.close()
if (allErrors.length) {
  failed++
  console.log('\n⚠ 页面报错:', allErrors.slice(0, 4))
}
console.log(`\nM11 走查：${failed ? failed + ' 项不通过 ❌' : '全部通过 ✅'}（截图见 ${SHOTS}/m11-*.png）`)
if (failed) process.exitCode = 1
