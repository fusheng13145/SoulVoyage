// M9 视觉走查：管理端六页（总览/任务监控/风险复核/内容管理/用户支持/审计密钥）深浅两态
// + 非管理员访问 /admin 的守卫回跳。
// 用法：后端 :8080、前端 :5173 起好后 `node deploy/tools/shots_m9.mjs`
// puppeteer-core 装在本目录（deploy/tools/npm install），与前端依赖图隔离，不受 npm ci 影响
import { mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const puppeteer = require('puppeteer-core');

const BASE = 'http://localhost:8080/api/v1';
const APP = 'http://localhost:5173';
const SHOTS = 'docs/screenshots';
mkdirSync(SHOTS, { recursive: true });
const PW = 'Passw0rd!2026';

async function api(method, p, body, token) {
  const res = await fetch(BASE + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json();
  if (json.code !== 0) throw new Error(`${method} ${p} -> ${json.code} ${json.msg}`);
  return json.data;
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ---- 1) 演示数据：一条待复核危机 + 一次日记流水线 + 一份待领取导出 ----
const user = 'm9_ui_' + Date.now().toString(36).slice(-6);
const { accessToken } = await api('POST', '/auth/register', { username: user, password: PW, nickname: '待守护的同学' });
const sid = (await api('POST', '/companion/sessions', {}, accessToken)).sessionId;
await fetch(BASE + `/companion/sessions/${sid}/turns`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${accessToken}` },
  body: JSON.stringify({ userText: '这半年一直在投简历，每次面试完都觉得更糟。不想活了。' }),
}).then(r => r.text());
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: { diaryText: '组会汇报被打断三次，回宿舍一路上都在想刚才那句话该怎么接。', recordDate: new Date().toISOString().slice(0, 10), moodSelfRating: 2 },
  clientReqId: 'm9shot-' + Date.now(),
}, accessToken);
for (let i = 0; i < 90; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, accessToken);
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break;
  await sleep(2000);
}
const exp = await api('GET', '/users/me/data-export', undefined, accessToken);
const adminToken = (await api('POST', '/auth/login', { username: 'admin', password: PW })).accessToken;
console.log('演示数据就绪:', user, 'task=', taskNo, 'export=', exp.fileId || exp.fileRef || JSON.stringify(exp).slice(0, 60));

// ---- 2) 无头 Edge 截图 ----
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=430,932'],
});
const page = await browser.newPage();
await page.setViewport({ width: 430, height: 932, deviceScaleFactor: 2 });
const errors = [];
page.on('pageerror', e => errors.push(String(e)));

async function loginAs(token, theme) {
  await page.goto(APP + '/login');
  await page.evaluate((t, th) => {
    localStorage.setItem('sv_access', t);
    localStorage.setItem('sv_refresh', '');
    localStorage.setItem('sv_theme', th);
  }, token, theme);
}

const shoot = async (route, name, wait = 1200) => {
  await page.goto(APP + route, { waitUntil: 'networkidle0' });
  await sleep(wait);
  await page.screenshot({ path: `${SHOTS}/m9-${name}.png` });
  console.log('  📷', name);
};

await loginAs(adminToken, 'light');
await shoot('/admin', 'light-home');
await shoot('/admin/tasks', 'light-tasks');
// 任务详情时间线：点第一行
await page.goto(APP + '/admin/tasks', { waitUntil: 'networkidle0' });
await sleep(1000);
const openedTask = await page.evaluate(() => {
  const el = [...document.querySelectorAll('button, a, [role="button"], .row, li')].find(x => /T[0-9A-Z]{10,}/.test(x.textContent || ''));
  if (el) el.click();
  return !!el;
});
await sleep(1200);
await page.screenshot({ path: `${SHOTS}/m9-light-task-detail.png` });
console.log('  📷 light-task-detail (点中任务行:', openedTask, ')');

await shoot('/admin/risk', 'light-risk');
// 复核抽屉：填口令 → 查看证据（解密后的原文定位）
await page.goto(APP + '/admin/risk', { waitUntil: 'networkidle0' });
await sleep(1000);
const openedRisk = await page.evaluate((pw) => {
  const input = document.querySelector('input[type="password"]');
  if (!input) return false;
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  setter.call(input, pw);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  const el = [...document.querySelectorAll('button')].find(b => /查看证据/.test(b.textContent || ''));
  if (el) el.click();
  return !!el;
}, PW);
await sleep(1500);
await page.screenshot({ path: `${SHOTS}/m9-light-risk-reveal.png` });
console.log('  📷 light-risk-reveal (点中复核入口:', openedRisk, ')');

await shoot('/admin/content', 'light-content');
await shoot('/admin/users', 'light-users');
await shoot('/admin/ops', 'light-ops');

await page.evaluate(() => localStorage.setItem('sv_theme', 'dark'));
await shoot('/admin', 'dark-home');
await shoot('/admin/risk', 'dark-risk');
await shoot('/admin/ops', 'dark-ops');

// ---- 3) 角色守卫：普通用户进 /admin 应被弹回用户端 ----
await loginAs(accessToken, 'light');
await page.goto(APP + '/admin', { waitUntil: 'networkidle0' });
await sleep(1000);
const landed = new URL(page.url()).pathname;
await page.screenshot({ path: `${SHOTS}/m9-light-guard.png` });
console.log('  📷 light-guard 非管理员访问 /admin 落点:', landed);
if (landed.startsWith('/admin')) { console.error('⚠ 管理端角色守卫失效'); process.exitCode = 1; }

await browser.close();
if (errors.length) { console.log('⚠ 页面报错:', errors); process.exitCode = 1; }
console.log('\nM9 管理端截图走查完成 ✅');
