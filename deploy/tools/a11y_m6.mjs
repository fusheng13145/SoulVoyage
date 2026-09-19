// M6 Lighthouse 无障碍：登录态三页 a11y 评分（用法：node deploy/tools/a11y_m6.mjs <username>）
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(path.resolve('frontend/package.json'));
const puppeteer = require('puppeteer-core');
const lighthouse = (await import(pathToFileURL(require.resolve('lighthouse')).href)).default;

const BASE = 'http://localhost:8080/api/v1';
const APP = 'http://localhost:5174';
const PW = 'Passw0rd!2026';

const login = async () => {
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.argv[2], password: PW }),
  });
  const j = await res.json();
  if (j.code !== 0) throw new Error('登录失败: ' + j.msg);
  return j.data.accessToken;
};

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: 'new',
});
const page = await browser.newPage();
await page.setViewport({ width: 430, height: 932, deviceScaleFactor: 2 });
await page.goto(APP + '/login');
await page.evaluate((tok) => {
  localStorage.setItem('sv_access', tok);
  localStorage.setItem('sv_theme', JSON.stringify('light'));
}, await login());

const port = Number(new URL(browser.wsEndpoint()).port);
for (const [route, name] of [['/today', 'today'], ['/diaries', 'diaries'], ['/insights', 'insights'], ['/me', 'me'], ['/practice', 'practice']]) {
  const { lhr } = await lighthouse(APP + route, {
    port, output: 'json', onlyCategories: ['accessibility'],
    formFactor: 'mobile', screenEmulation: { width: 430, height: 932, deviceScaleFactor: 2, mobile: true },
    throttlingMethod: 'detect',
  });
  console.log(`♿ a11y ${name}: ${Math.round(lhr.categories.accessibility.score * 100)}`);
  for (const a of Object.values(lhr.audits))
    if (a.score !== null && a.score < 1 && a.details?.nodeCount)
      console.log('   -', a.id, a.score, a.details.nodeCount, (a.details.items || []).slice(0, 3).map(i => i.node?.selector || '').join(' | '));
}
await browser.close();
