// M6 视觉走查 + Lighthouse 无障碍：无头 Edge 截深浅两态五 Tab，再跑 a11y 评分
// 用法：后端 :8080、前端 :5174 起好后 `node deploy/tools/shots_m6.mjs`（puppeteer-core/lighthouse 需先装在 frontend/node_modules）
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(path.resolve('frontend/package.json'));
const puppeteer = require('puppeteer-core');
const lighthouse = (await import(pathToFileURL(require.resolve('lighthouse')).href)).default;

const BASE = 'http://localhost:8080/api/v1';
const APP = 'http://localhost:5174';
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
const enCA = (d) => d.toLocaleDateString('en-CA');

// ---- 1) 演示账号：回填 10 天打卡 + 跑一篇真实日记管线，让截图有数据 ----
const user = 'm6_ui_' + Date.now().toString(36).slice(-6);
let { accessToken } = await api('POST', '/auth/register', { username: user, password: PW, nickname: '走查员' });
const MOODS = [['开心', 0.6], ['平静', 0.4], ['疲惫', -0.3], ['烦躁', -0.5], ['期待', 0.5], ['难过', -0.6], ['专注', 0.3]];
for (let i = 1; i <= 10; i++) {
  const [emo, val] = MOODS[i % MOODS.length];
  await api('POST', '/emotions/self-rating',
    { emotion: emo, valence: val, intensity: 0.3 + (i % 4) * 0.15, date: enCA(new Date(Date.now() - i * 864e5)) }, accessToken);
}
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: { diaryText: '今天小组会把我的方案当众改了一遍，我嘴上没说什么，心里很憋，晚上一直睡不着。', recordDate: enCA(new Date()), moodSelfRating: 2 },
  clientReqId: 'm6shot-' + Date.now(),
}, accessToken);
for (let i = 0; i < 90; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, accessToken);
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break;
  await new Promise(r => setTimeout(r, 2000));
}
console.log('演示数据就绪：', user, 'task=', taskNo);

// ---- 2) 无头 Edge 截图 ----
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=430,932'],
});
const page = await browser.newPage();
await page.setViewport({ width: 430, height: 932, deviceScaleFactor: 2 });
await page.goto(APP + '/login');
await page.evaluate((tok) => {
  localStorage.setItem('sv_access', tok);
  localStorage.setItem('sv_refresh', '');
  localStorage.setItem('sv_theme', 'light');
}, accessToken);

const shoot = async (route, name) => {
  await page.goto(APP + route, { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 900)); // 转场/图表动画落定
  await page.screenshot({ path: `${SHOTS}/m6-${name}.png` });
  console.log('  📷', name);
};
const tabs = [['/today', 'today'], ['/diaries', 'diaries'], ['/practice', 'practice'], ['/insights', 'insights'], ['/me', 'me']];
for (const [r, n] of tabs) await shoot(r, 'light-' + n);
await shoot('/diaries/write', 'light-write');
await shoot('/crisis', 'light-crisis');
// 报告详情（C2）：取列表第一篇
const reports = await api('GET', '/reports?page=0&size=1', undefined, accessToken);
if (reports.items?.length) await shoot(`/archive/report/${reports.items[0].id}`, 'light-report');
// 深色态
await page.evaluate(() => localStorage.setItem('sv_theme', 'dark'));
for (const [r, n] of tabs) await shoot(r, 'dark-' + n);

// 控制台报错扫描（走查期间收集）
let errors = [];
page.on('pageerror', e => errors.push(String(e)));

// ---- 3) Lighthouse a11y（复用同一实例：localStorage 仍在，可进登录态页）----
const port = Number(new URL(browser.wsEndpoint()).port);
for (const [route, name] of [['/today', 'today'], ['/insights', 'insights'], ['/me', 'me']]) {
  const { lhr } = await lighthouse(APP + route, {
    port, output: 'json', onlyCategories: ['accessibility'],
    formFactor: 'mobile', screenEmulation: { width: 430, height: 932, deviceScaleFactor: 2, mobile: true },
    throttlingMethod: 'detect',
  });
  console.log(`  ♿ a11y ${name}: ${Math.round(lhr.categories.accessibility.score * 100)}`);
  for (const a of Object.values(lhr.audits).filter(x => x.score !== null && x.score < 0.9))
    console.log('     -', a.id, a.score);
}
await browser.close();
console.log('\nM6 截图 + a11y 审计完成 ✅');
