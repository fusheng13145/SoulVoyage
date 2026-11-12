// Batch 0 截图采集：4 宽度 × 明暗 × 7 路由，写入 docs/baseline/screenshots/
// 前置：后端 :8080、前端 :5174（可用 SV_APP 覆盖）已启动；本机 Edge 可用
// 用法：node deploy/tools/snapshots_batch0.mjs
// 说明：只为基线留痕，不改代码；账号通过 API 注册并注入 localStorage。
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.resolve('deploy/tools/package.json'));
const puppeteer = require('puppeteer-core');

const BASE = (process.env.SV_ORIGIN || 'http://localhost:8080') + '/api/v1';
const APP = process.env.SV_APP || 'http://localhost:5174';
const OUT = process.env.SV_SHOT_OUT || 'docs/baseline/screenshots';
const PW = 'Passw0rd!2026';
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const EN_CA = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' });

mkdirSync(OUT, { recursive: true });

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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 1) 演示数据：注册 + 近 7 天自评 + 3 天打卡 + 一篇日记（生成计划/报告） ----
const user = `base_ui_${Date.now().toString(36)}`;
const { accessToken } = await api('POST', '/auth/register', { username: user, password: PW, nickname: '基线走查' });
const MOODS = [['开心', 0.6], ['平静', 0.4], ['疲惫', -0.3], ['烦躁', -0.5], ['期待', 0.5], ['难过', -0.6], ['专注', 0.3]];
for (let i = 0; i < 7; i++) {
  const [emotion, valence] = MOODS[i % MOODS.length];
  const date = EN_CA.format(new Date(Date.now() - i * 864e5));
  await api('POST', '/emotions/self-rating', { emotion, valence, intensity: 0.3 + (i % 4) * 0.15, date }, accessToken);
  await api('POST', '/mood-check-ins', {
    emotion: ['JOY', 'CALM', 'TIRED'][i % 3], rating: 2 + (i % 3), energy: 2 + (i % 3),
    note: `基线走查第 ${i + 1} 天`,
  }, accessToken);
}
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: {
    diaryText: '今天小组会把我的方案当众改了一遍，我嘴上没说什么，心里很憋，晚上一直睡不着。',
    recordDate: EN_CA.format(new Date()),
    moodSelfRating: 2,
  },
  clientReqId: `baseui-${Date.now()}`,
}, accessToken);
for (let i = 0; i < 90; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, accessToken);
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break;
  await sleep(1000);
}
console.log('演示数据就绪：', user, 'task=', taskNo);

// ---- 2) 无头 Edge 截图 ----
const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: 'new',
  args: ['--no-first-run', '--disable-gpu'],
});
const page = await browser.newPage();

const TABS = [
  ['/today', 'today'],
  ['/diaries', 'diaries'],
  ['/diaries/write', 'write'],
  ['/insights', 'insights'],
  ['/practice', 'practice'],
  ['/me', 'me'],
  ['/crisis', 'crisis'],
];
const WIDTHS = [375, 768, 1024, 1440];
const THEMES = ['light', 'dark'];

for (const width of WIDTHS) {
  await page.setViewport({ width, height: width < 768 ? 812 : 900, deviceScaleFactor: 1 });
  for (const theme of THEMES) {
    await page.goto(APP + '/login', { waitUntil: 'domcontentloaded' });
    await page.evaluate((tok, th) => {
      localStorage.setItem('sv_access', tok);
      localStorage.setItem('sv_refresh', '');
      localStorage.setItem('sv_theme', th);
    }, accessToken, theme);

    for (const [route, name] of TABS) {
      const file = `${OUT}/${width}-${theme}-${name}.png`;
      try {
        await page.goto(APP + route, { waitUntil: 'networkidle2', timeout: 30000 });
      } catch { /* 图表页可能持续轮询，超时也继续截 */ }
      await sleep(route === '/insights' ? 1600 : 900);
      await page.screenshot({ path: file });
      console.log('  shot', file);
    }

    // 未登录态：清掉令牌再截 /login（保留主题）
    await page.evaluate(() => {
      localStorage.removeItem('sv_access');
      localStorage.removeItem('sv_refresh');
    });
    const loginFile = `${OUT}/${width}-${theme}-login.png`;
    try {
      await page.goto(APP + '/login', { waitUntil: 'networkidle2', timeout: 30000 });
    } catch { /* 继续截 */ }
    await sleep(600);
    await page.screenshot({ path: loginFile });
    console.log('  shot', loginFile);
  }
}

await browser.close();
console.log('截图完成：', OUT);
