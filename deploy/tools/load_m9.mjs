// M9 压测（O4/手册验收：1k DAU 首轮数据）。
// 模型：1k DAU 集中在活跃小时，均值 ≈ 1k×8req / 3600s ≈ 2.2 req/s；本机资源有限，
// 默认 --users 40 --think 36s（≈1.1 req/s）等效一半流量，参数可放大到全速。
// Mock LLM 下游（SV_LLM_PROVIDER=mock），量的是本平台自身链路（Controller/Service/DB/Redis），不含外部模型时延。
// 前置：dev 后端起在 :8080；admin 需已提权（脚本自动探测并给出提示）。
// 用法：node deploy/tools/load_m9.mjs [--users 40] [--think 36] [--duration 300] [--base http://localhost:8080]
//      [--out 落盘路径] [--llm 下游口径] [--note 备注]——M10 复测真协议下游：
//      node deploy/tools/load_m9.mjs --out deploy/load_m10_results.json \
//        --llm "openai-compat stub @127.0.0.1:8787" --note "LLM 走真 HTTP/SSE，含桩侧 120ms 首包 + 15ms/块"
const BASE = (apiVal('--base') || 'http://localhost:8080') + '/api/v1';
const USERS = Number(apiVal('--users') || 40);
const THINK_MS = Number(apiVal('--think') || 36000);
const DURATION_MS = Number(apiVal('--duration') || 300000);
const PW = 'Passw0rd!2026';

function apiVal(name) {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : '';
}

// 端点权重（真实 App 的读多写少画像）：GET 浏览为主，穿插登录/写操作/管理端
const MIX = [
  { w: 14, name: 'GET /diaries', run: u => call('GET', '/diaries', null, { page: 0, size: 5 }, u) },
  { w: 12, name: 'GET /readings/today', run: u => call('GET', '/readings/today', null, null, u) },
  { w: 10, name: 'GET /tasks', run: u => call('GET', '/tasks', null, { page: 0, size: 5 }, u) },
  { w: 8, name: 'GET /scenes', run: u => call('GET', '/scenes', null, null, u) },
  { w: 8, name: 'GET /exercises', run: u => call('GET', '/exercises', null, null, u) },
  { w: 7, name: 'PUT /diaries/draft', run: u => call('PUT', '/diaries/draft', { content: '今天例会发言有点紧张，但整体还行，晚上补了一节跟练。' + Date.now() }, null, u) },
  { w: 6, name: 'POST /companion turn', run: companionTurn },
  { w: 5, name: 'POST /simulations+interrupt', run: simOpenInterrupt },
  { w: 6, name: 'POST /mood-check-ins', run: u => call('POST', '/mood-check-ins', { emotion: 'CALM', rating: 4, energy: 3, note: '压测打卡' }, null, u) },
  { w: 5, name: 'GET /mood-check-ins?month', run: u => call('GET', '/mood-check-ins', null, { month: monthNow() }, u) },
  { w: 4, name: 'GET /archive/summary', run: u => call('GET', '/archive/summary', null, null, u) },
  { w: 3, name: 'GET /plans', run: u => call('GET', '/plans', null, null, u) },
  { w: 3, name: 'POST /auth/login', run: relogin },
  { w: 2, name: 'POST /readings/favorites', run: favorite },
  // 管理端低频（1 个专职管理员）
  { w: 3, name: 'GET /admin/metrics/overview', run: u => call('GET', '/admin/metrics/overview', null, { days: 7 }, u), admin: true },
  { w: 2, name: 'GET /admin/tasks', run: u => call('GET', '/admin/tasks', null, { page: 0, size: 10 }, u), admin: true },
];
const MIX_NORMAL = MIX.filter(m => !m.admin);
const MIX_ADMIN = MIX.filter(m => m.admin);
const MIX_TOTAL = MIX_NORMAL.reduce((s, m) => s + m.w, 0);
const MIX_ADMIN_TOTAL = MIX_ADMIN.reduce((s, m) => s + m.w, 0);
const monthNow = () => new Date().toISOString().slice(0, 7);

async function call(method, path, body, params, u, _retry) {
  const url = new URL(BASE + path);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const t = u.admin ? adminToken : u.token;
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  // 长跑跨 access-token TTL（30m）：401 时静默重登一次再重试
  if (res.status === 401 && !_retry && !u.admin) {
    const d = await fetch(BASE + '/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: u.username, password: PW }),
    }).then(r => r.json()).catch(() => null);
    if (d?.data?.accessToken) {
      u.token = d.data.accessToken;
      return call(method, path, body, params, u, true);
    }
  }
  const json = await res.json().catch(() => null);
  if (!res.ok || json?.code !== 0) throw new Error(`${method} ${path} -> ${res.status}/${json?.code ?? '?'} ${json?.msg ?? ''}`.slice(0, 160));
  return json.data;
}

/** 消费 SSE 响应直到流关闭；无 turn_done/finished 事件视为失败 */
async function sseCall(method, path, body, u) {
  const t = u.admin ? adminToken : u.token;
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream',
      ...(t ? { Authorization: `Bearer ${t}` } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}`);
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', event = '', got = false;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:') && /"crisis"|turn_done|session_done/.test(line)) got = true;
    }
  }
  if (!got) throw new Error(`${method} ${path} 流内无收口事件`);
}

/** 漫聊一轮：开 session → SSE 发一轮（Mock LLM 分段下发）→ 结束会话 */
async function companionTurn(u) {
  const s = await call('POST', '/companion/sessions', {}, null, u);
  try {
    await sseCall('POST', `/companion/sessions/${s.sessionId}/turns`,
      { userText: '最近换季，晚上总是睡不踏实，白天没什么精神。' }, u);
  } finally {
    await call('POST', `/companion/sessions/${s.sessionId}/end`, {}, null, u).catch(() => {});
  }
}

/** 开一场模拟并中途退出（不触发复盘管线，压的是会话链路） */
async function simOpenInterrupt(u) {
  const s = await call('POST', '/simulations', { sceneCode: 'DORM_CONFLICT', difficulty: 'NORMAL' }, null, u);
  await call('POST', `/simulations/${s.simulateId}/interrupt`, {}, null, u);
}

async function relogin(u) {
  if (u.admin) return;   // 管理员令牌是手工提权的，重登会退回 USER 角色
  u.token = (await call('POST', '/auth/login', { username: u.username, password: PW }, null, u)).accessToken;
}

async function favorite(u) {
  const card = await call('GET', '/readings/today', null, null, u);
  await call('POST', '/readings/favorites', { type: 'PSY_TOPIC', refCode: card.kgNodeId }, null, u);
}

function pick(roll, mix) {
  let acc = 0;
  for (const m of mix) { acc += m.w; if (roll < acc) return m; }
  return mix[mix.length - 1];
}

const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];

// 休眠守卫：墙钟漂移远超计划窗口时判本轮无效（合盖睡眠会让 setTimeout 冻结、令牌全过期）
function guardSleep(tag) {
  const wall = (Date.now() - t0) / 1000;
  if (wall > DURATION_MS / 1000 + 120) {
    console.error(`× 本机在${tag}出现休眠/长冻结（墙钟 ${wall.toFixed(0)}s ≫ 计划 ${DURATION_MS / 1000}s），本轮数据无效，请保持不休眠后重跑`);
    process.exit(2);
  }
}

// ── 准备：后端就绪 + 管理员 + 压测用户池 ──
let adminToken = '';
for (let i = 0; ; i++) {
  try {
    const { status } = await fetch(BASE + '/scenes').then(r => ({ status: r.status }));
    if (status < 500) break;
  } catch { /* 未就绪 */ }
  if (i > 60) throw new Error('后端 60s 未就绪');
  await new Promise(r => setTimeout(r, 1000));
}

{
  const login = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PW }),
  }).then(r => r.json()).catch(() => null);
  adminToken = login?.data?.accessToken || '';
  if (!adminToken) {
    const reg = await fetch(BASE + '/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'load_m9_admin', password: PW, nickname: '压测管理' }),
    }).then(r => r.json());
    adminToken = reg?.data?.accessToken || '';
  }
  if (adminToken) {
    const probe = await fetch(BASE + '/admin/metrics/overview?days=7',
      { headers: { Authorization: `Bearer ${adminToken}` } }).then(r => r.status);
    if (probe !== 200) adminToken = '';
  }
  if (!adminToken) console.log('⚠ 管理员不可用：跳过 admin 端点。请在 dev 库执行 UPDATE `user` SET role=\'ADMIN\' WHERE username=\'admin\'; 后重跑');
}

const stamp = Date.now().toString(36);
const pool = [];
for (let i = 0; i < USERS; i++) {
  const username = `load_m9_${stamp}_${i}`;
  const d = await fetch(BASE + '/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PW, nickname: `压测${i}` }),
  }).then(r => r.json());
  if (!d?.data?.accessToken) throw new Error(`注册压测用户失败: ${JSON.stringify(d)}`);
  pool.push({ username, token: d.data.accessToken, admin: false, n: 0 });
}
const adminUser = adminToken ? { username: 'admin', token: adminToken, admin: true, n: 0 } : null;

const stats = new Map();               // name -> {ms:[], errs:Map<msg,count>}
const errors = [];
const t0 = Date.now();
let stopAt = t0 + DURATION_MS;
console.log(`压测开始：users=${USERS} think=${THINK_MS}ms duration=${DURATION_MS / 1000}s base=${BASE}`);

async function runLoop(u, mix, mixTotal, staggerMs) {
  await new Promise(r => setTimeout(r, staggerMs));   // 错开相位，避免整点惊群
  for (;;) {
    const m = pick(Math.random() * mixTotal, mix);
    const s = performance.now();
    let err = null;
    try { await m.run(u); u.n++; } catch (e) { err = e; }
    const cost = performance.now() - s;
    if (!stats.has(m.name)) stats.set(m.name, { ms: [], errs: new Map() });
    const st = stats.get(m.name);
    st.ms.push(cost);
    if (err) {
      const key = String(err.message).slice(0, 120);
      st.errs.set(key, (st.errs.get(key) || 0) + 1);
      if (errors.length < 50) errors.push(`${new Date().toISOString()} ${m.name}: ${key}`);
    }
    const left = stopAt - Date.now();
    if (left <= 0) return;
    guardSleep('loop');
    await new Promise(r => setTimeout(r, Math.min(THINK_MS, left) * (0.5 + Math.random())));
  }
}

const workers = pool.map((u, idx) => runLoop(u, MIX_NORMAL, MIX_TOTAL, (idx * THINK_MS) / USERS));
if (adminUser) workers.push(runLoop(adminUser, MIX_ADMIN, MIX_ADMIN_TOTAL, 0));

await Promise.all(workers);
guardSleep('final');
const elapsed = (Date.now() - t0) / 1000;

// ── 汇总 ──
const all = [];
const rows = [];
for (const [name, st] of [...stats].sort((a, b) => b[1].ms.length - a[1].ms.length)) {
  st.ms.sort((a, b) => a - b);
  all.push(...st.ms);
  const errN = [...st.errs.values()].reduce((s, v) => s + v, 0);
  rows.push({ endpoint: name, n: st.ms.length, errRate: +(errN / st.ms.length * 100).toFixed(2),
    p50: +pct(st.ms, 0.5).toFixed(1), p95: +pct(st.ms, 0.95).toFixed(1),
    p99: +pct(st.ms, 0.99).toFixed(1), max: +st.ms[st.ms.length - 1].toFixed(1) });
  for (const [msg, c] of st.errs) rows[rows.length - 1]['err#' + msg] = c;
}
all.sort((a, b) => a - b);
const totalN = all.length;
const totalErr = rows.reduce((s, r) => s + Math.round(r.errRate * r.n / 100), 0);
const summary = {
  when: new Date().toISOString(), base: BASE,
  users: USERS, thinkMs: THINK_MS, seconds: +elapsed.toFixed(1),
  requests: totalN, rps: +(totalN / elapsed).toFixed(2),
  overallErrPct: +(totalErr / totalN * 100).toFixed(2),
  p50: +pct(all, 0.5).toFixed(1), p95: +pct(all, 0.95).toFixed(1),
  p99: +pct(all, 0.99).toFixed(1), max: +all[all.length - 1].toFixed(1),
  llm: (apiVal('--llm') || 'in-process mock') + '；' + (apiVal('--note') || ''),
  model: '1k DAU ≈ 2.2 req/s（活跃小时均值）；本轮按参数折算' + (+(totalN / elapsed / 2.2).toFixed(2)) + 'x 目标流量',
  perEndpoint: rows,
};
const out = apiVal('--out') || 'deploy/load_m9_results.json';
const { writeFileSync } = await import('node:fs');
writeFileSync(out, JSON.stringify(summary, null, 2));
console.table(rows.map(({ endpoint, n, errRate, p50, p95, p99, max }) => ({ endpoint, n, errRate, p50, p95, p99, max })));
console.log(`总请求 ${totalN} · RPS ${summary.rps} · 错误率 ${summary.overallErrPct}% · p50 ${summary.p50}ms · p95 ${summary.p95}ms · p99 ${summary.p99}ms · max ${summary.max}ms`);
if (errors.length) console.log('样例错误:\n' + errors.slice(0, 10).join('\n'));
console.log(`明细已写入 ${out}`);
