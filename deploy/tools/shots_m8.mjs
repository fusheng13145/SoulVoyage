// M8 视觉走查：每日一读+最近动态（今日页）/ 场景标签与训练档案（训练页）/ 续练回放 / 收藏（档案页）/ 报告引用案例
// 用法：后端 :8080、前端 :5173 起好后 `node deploy/tools/shots_m8.mjs`（puppeteer-core 装在 frontend/node_modules）
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.resolve('frontend/package.json'));
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
const enCA = (d) => d.toLocaleDateString('en-CA');

// ---- 1) 演示账号：画像（近 4 周压力源=学业/就业）+ 自评轨迹 + 一篇日记任务 + 一次完整训练 + 收藏 ----
const user = 'm8_ui_' + Date.now().toString(36).slice(-6);
let { accessToken } = await api('POST', '/auth/register', { username: user, password: PW, nickname: '走查员' });
const MOODS = [['焦虑', 0.6], ['疲惫', -0.3], ['烦躁', -0.5], ['期待', 0.5]];
for (let i = 1; i <= 8; i++) {
  const [emo, val] = MOODS[i % MOODS.length];
  await api('POST', '/emotions/self-rating',
    { emotion: emo, valence: val, intensity: 0.4 + (i % 3) * 0.15, note: '晚自习后心跳很快', date: enCA(new Date(Date.now() - i * 864e5)) }, accessToken);
}
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: { diaryText: '缓考申请被导师驳回那晚，我把简历改到三点，越改越慌。', recordDate: enCA(new Date()), moodSelfRating: 2 },
  clientReqId: 'm8shot-' + Date.now(),
}, accessToken);
for (let i = 0; i < 90; i++) {
  const t = await api('GET', `/tasks/${taskNo}`, undefined, accessToken);
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) break;
  await new Promise(r => setTimeout(r, 2000));
}

// 一次真实训练 + 复盘（喂训练档案/报告），再一次中断会话（喂"上次没练完"）
const sim = await api('POST', '/simulations', { sceneCode: 'TEACHER_EXTENSION', difficulty: 'NORMAL' }, accessToken);
for (const text of [
  '老师，我上周发烧缺了两次课，缓考申请被驳回了，我想知道还有没有别的补救通道',
  '我理解成绩认定有流程，我能不能先提交病假证明，再约您当面说明情况',
]) {
  const rs = await fetch(BASE + `/simulations/${sim.simulateId}/turns`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ userText: text }),
  });
  await rs.text();
}
const fin = await api('POST', `/simulations/${sim.simulateId}/finish`, undefined, accessToken);
let simTask;
for (let i = 0; i < 150; i++) {
  simTask = await api('GET', `/tasks/${fin.taskNo}`, undefined, accessToken);
  if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(simTask.status)) break;
  await new Promise(r => setTimeout(r, 200));
}
const sim2 = await api('POST', '/simulations', { sceneCode: 'MONEY_REFUSE', difficulty: 'MILD' }, accessToken);
{
  const rs = await fetch(BASE + `/simulations/${sim2.simulateId}/turns`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ userText: '陈默，你上次说的那笔钱我这两天确实手头也紧' }),
  });
  await rs.text();
}
await api('POST', `/simulations/${sim2.simulateId}/interrupt`, undefined, accessToken);

// 今日一读收藏（喂档案页"我的收藏"）
const reading = await api('GET', '/readings/today', undefined, accessToken);
await api('POST', '/readings/favorites', { type: 'PSY_TOPIC', refCode: reading.kgNodeId }, accessToken);
console.log('演示数据就绪：', user, 'sim=', sim.simulateId, 'interrupted=', sim2.simulateId, 'task=', simTask.status);

// ---- 2) 无头 Edge 截图 ----
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
  args: ['--no-first-run', '--window-size=430,932'],
});
const page = await browser.newPage();
await page.setViewport({ width: 430, height: 932, deviceScaleFactor: 2 });
let errors = [];
page.on('pageerror', e => errors.push(String(e)));
await page.goto(APP + '/login');
await page.evaluate((tok) => {
  localStorage.setItem('sv_access', tok);
  localStorage.setItem('sv_refresh', '');
  localStorage.setItem('sv_theme', 'light');
}, accessToken);

const shoot = async (route, name) => {
  await page.goto(APP + route, { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 900));
  await page.screenshot({ path: `${SHOTS}/m8-${name}.png` });
  console.log('  📷', name);
};

await shoot('/today', 'light-today');            // 每日一读卡 + 最近动态 feed
await shoot('/practice/sim', 'light-sim-scenes'); // 10 卡 + 标签栏 + 推荐 + 没练完/最佳分
// 续练回放：点"上次没练完 → 继续练"
await page.goto(APP + '/practice/sim', { waitUntil: 'networkidle0' });
await new Promise(r => setTimeout(r, 900));
const resumed = await page.evaluate(() => {
  const el = [...document.querySelectorAll('button')].find(b => b.textContent.includes('继续练'));
  if (el) { el.click(); return true; }
  return false;
});
await new Promise(r => setTimeout(r, 1200));
await page.screenshot({ path: `${SHOTS}/m8-light-resume.png` });
console.log('  📷 light-resume (点中继续卡:', resumed, ')');

await shoot('/archive', 'light-archive');         // 我的收藏
const reports = await api('GET', '/reports?page=0&size=5', undefined, accessToken);
const simRp = reports.items.find(x => x.type === 'SIMULATE');
if (simRp) await shoot(`/archive/report/${simRp.id}`, 'light-report');

await page.evaluate(() => localStorage.setItem('sv_theme', 'dark'));
await shoot('/today', 'dark-today');
await shoot('/practice/sim', 'dark-sim-scenes');
await shoot('/archive', 'dark-archive');

await browser.close();
if (errors.length) { console.log('⚠ 页面报错:', errors); process.exitCode = 1; }
console.log('\nM8 截图走查完成 ✅');
