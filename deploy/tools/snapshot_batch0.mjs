// Batch 0 golden 采集：按 Spec §3.6 的 15 个场景调用真实后端，结果脱敏后写入 docs/baseline/golden/
// 前置：MySQL/Redis 已起；后端已起在 :8080（可用 SV_ORIGIN 覆盖）；admin 账号可登录（不可用时 14/15 记 SKIPPED）
// 用法：node deploy/tools/snapshot_batch0.mjs
// 说明：只读/写测试数据，不改代码与表结构；任一步失败写入 status=FAILED 并继续，末尾汇总退出码。
import { mkdirSync, writeFileSync } from 'node:fs';

const BASE = (process.env.SV_ORIGIN || 'http://localhost:8080') + '/api/v1';
const OUT = 'docs/baseline/golden';
const PW = 'Passw0rd!2026';
const EN_CA = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' });
const today = () => EN_CA.format(new Date());
const daysAgo = (n) => EN_CA.format(new Date(Date.now() - n * 864e5));
const ts = Date.now().toString(36);

mkdirSync(OUT, { recursive: true });

const failures = [];
const skips = [];

function save(name, payload) {
  writeFileSync(`${OUT}/${name}.json`, JSON.stringify(sanitize(payload), null, 2) + '\n', 'utf8');
  console.log('  saved', name + '.json');
}

/** 脱敏：键排序 + 去除随机/时间/身份字段（与 manifest 的忽略清单保持一致） */
const REDACT_KEYS = new Set([
  'id', 'userId', 'sessionId', 'simulateId', 'turnId', 'fileId', 'taskId', 'groupId', 'planId', 'riskEventId',
  'accessToken', 'refreshToken', 'token', 'bindTicket', 'openid', 'wxOpenid', 'username', 'nickname',
  'taskNo', 'traceId', 'clientReqId',
]);
function sanitize(v) {
  if (Array.isArray(v)) return v.map(sanitize);
  if (v && typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v).sort()) {
      const redact = REDACT_KEYS.has(k) || /At$/.test(k);
      out[k] = redact && v[k] !== null && v[k] !== '' ? '<redacted>' : sanitize(v[k]);
    }
    return out;
  }
  return v;
}

async function req(method, path, body, token, params) {
  const url = new URL(BASE + path);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = {};
  try { json = JSON.parse(text); } catch { /* 非 JSON 留给调用方 */ }
  return { status: res.status, code: json.code, msg: json.msg, data: json.data, text };
}
async function api(method, path, body, token, params) {
  const r = await req(method, path, body, token, params);
  if (r.code !== 0) throw new Error(`${method} ${path} -> HTTP ${r.status} code=${r.code} ${r.msg || ''}`);
  return r.data;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 收集 SSE 事件（POST 轮次 / GET 任务流） */
async function sse(path, { method = 'GET', body, token, timeoutMs = 180000 } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: {
        Accept: 'text/event-stream',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status}`);
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    const events = [];
    let buf = '';
    let current = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).replace(/\r$/, '');
        buf = buf.slice(nl + 1);
        if (line.startsWith('event:')) current = line.slice(6).trim();
        else if (line.startsWith('data:')) {
          try { events.push({ event: current, data: JSON.parse(line.slice(5).trim()) }); } catch { /* 心跳 */ }
        }
      }
    }
    return events;
  } finally {
    clearTimeout(timer);
  }
}

const TERMINAL = ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED'];
async function waitTask(taskNo, token, maxMs = 180000) {
  const polls = [];
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    const t = await api('GET', `/tasks/${taskNo}`, undefined, token);
    polls.push(t);
    if (TERMINAL.includes(t.status)) return { task: t, polls };
    await sleep(1000);
  }
  throw new Error(`任务 ${taskNo} 超时未到终态`);
}
/** 任务终态后回放 SSE：各步产物在 middle_result，末步合并产物在 done.payload */
async function finalsOf(taskNo, token) {
  const events = await sse(`/tasks/${taskNo}/stream`, { token, timeoutMs: 60000 });
  const finals = {};
  let done = null;
  for (const e of events) {
    if (e.event === 'middle_result' && e.data?.agent) finals[e.data.agent] = e.data.payload;
    if (e.event === 'done') done = e.data?.payload ?? null;
  }
  return { eventCount: events.length, finals, done };
}

async function scenario(no, name, note, fn) {
  console.log(`\n[${no}] ${name}`);
  try {
    const steps = await fn();
    save(`${no}-${name}`, { scenario: name, note, steps });
    return true;
  } catch (err) {
    failures.push(`${name}: ${err.message}`);
    save(`${no}-${name}`, { scenario: name, note, status: 'FAILED', error: String(err.message || err) });
    console.error('  FAILED:', err.message);
    return false;
  }
}

// ---- 就绪探测：后端不可用即 BLOCKED，不伪造快照 ----
for (let i = 0; ; i++) {
  try {
    const r = await req('POST', '/auth/login', { username: 'baseline_probe', password: 'x' });
    if (r.status === 401 || r.status === 200) break;
  } catch { /* 未就绪 */ }
  if (i >= 60) {
    console.error('BLOCKED: 后端 60s 内未就绪（' + BASE + '），未写入任何 golden。');
    process.exit(2);
  }
  await sleep(1000);
}

const mainUser = `base_${ts}`;
const riskUser = `base_risk_${ts}`;
let access;      // 业务场景用的 accessToken（logout 后重新登录）
let mainUserId;

// 01 注册
await scenario(1, 'auth-register', '注册并返回令牌对', async () => {
  const body = { username: mainUser, password: PW, nickname: '基线用户' };
  const data = await api('POST', '/auth/register', body);
  mainUserId = data.userId;
  return [{ name: 'register', request: body, response: data }];
});

// 02 登录
let loginData;
await scenario(2, 'auth-login', '口令登录返回令牌对', async () => {
  const body = { username: mainUser, password: PW };
  loginData = await api('POST', '/auth/login', body);
  return [{ name: 'login', request: body, response: loginData }];
});

// 03 刷新（旧 refresh 用后即废）
let refreshed;
await scenario(3, 'auth-refresh', 'refresh 轮换', async () => {
  const body = { refreshToken: loginData.refreshToken };
  refreshed = await api('POST', '/auth/refresh', body);
  return [{ name: 'refresh', request: { refreshToken: '<redacted>' }, response: refreshed }];
});

// 04 登出（access 加入黑名单 + refresh 吊销）
await scenario(4, 'auth-logout', '登出吊销当前会话', async () => {
  const r = await api('POST', '/auth/logout', { refreshToken: refreshed.refreshToken }, refreshed.accessToken);
  return [{ name: 'logout', request: { refreshToken: '<redacted>' }, response: r }];
});

// 业务场景重新登录，避免使用已登出的令牌
access = (await api('POST', '/auth/login', { username: mainUser, password: PW })).accessToken;

// 05 日记提交 + 轮询 + final 产物
let diaryTaskNo;
await scenario(5, 'diary-task', 'DIARY_PIPELINE 提交→轮询终态→回放任务流产物', async () => {
  const body = {
    pipelineCode: 'DIARY_PIPELINE',
    payload: {
      diaryText: '今天小组会把我的方案当众改了一遍，我嘴上没说什么，心里很憋，晚上一直睡不着。',
      recordDate: today(),
      moodSelfRating: 2,
    },
    clientReqId: `base-${ts}-diary`,
  };
  const submit = await api('POST', '/tasks', body, access);
  diaryTaskNo = submit.taskNo;
  const { task, polls } = await waitTask(submit.taskNo, access);
  const { eventCount, finals, done } = await finalsOf(submit.taskNo, access);
  return [
    { name: 'submit', request: body, response: submit },
    { name: 'poll', response: { status: task.status, pollCount: polls.length } },
    { name: 'finals', response: { eventCount, agents: Object.keys(finals), done } },
  ];
});

// 06 高风险日记的 RISK_ARCHIVE 回执（独立账号，避免危机态污染其他场景）
await scenario(6, 'risk-receipt', '高危日记 → RISK_ARCHIVE 回执（任务流产物）', async () => {
  const reg = await api('POST', '/auth/register', { username: riskUser, password: PW, nickname: '基线高危' });
  const body = {
    pipelineCode: 'DIARY_PIPELINE',
    payload: {
      diaryText: '最近很累，有点不想活了，感觉撑不下去了。',
      recordDate: today(),
      moodSelfRating: 1,
    },
    clientReqId: `base-${ts}-risk`,
  };
  const submit = await api('POST', '/tasks', body, reg.accessToken);
  const { task } = await waitTask(submit.taskNo, reg.accessToken);
  const { finals, done } = await finalsOf(submit.taskNo, reg.accessToken);
  const receipt = finals.RISK_ARCHIVE ?? done;
  const referral = await api('GET', '/risk/resources');
  return [
    { name: 'submit', request: body, response: submit },
    { name: 'task', response: { status: task.status } },
    { name: 'receipt', response: receipt },
    { name: 'referral', response: referral },
  ];
});

// 07 人际模拟：开场景 → 一轮 SSE → finish → 轮询
await scenario(7, 'simulation', '模拟开场景→逐轮→结束→任务终态', async () => {
  const openBody = { sceneCode: 'DORM_CONFLICT', difficulty: 'NORMAL' };
  const opened = await api('POST', '/simulations', openBody, access);
  const turnEvents = await sse(`/simulations/${opened.simulateId}/turns`, {
    method: 'POST', body: { userText: '我想跟你商量熄灯后的安排' }, token: access,
  });
  const turnDone = turnEvents.find((e) => e.event === 'turn_done');
  const fin = await api('POST', `/simulations/${opened.simulateId}/finish`, {}, access);
  const { task } = await waitTask(fin.taskNo, access);
  const { finals } = await finalsOf(fin.taskNo, access);
  return [
    { name: 'open', request: openBody, response: { simulateId: '<redacted>', status: opened.status } },
    { name: 'turn', response: { events: turnEvents.length, turnDone: turnDone?.data ?? null } },
    { name: 'finish', response: { taskNo: '<redacted>' } },
    { name: 'task', response: { status: task.status, finalAgents: Object.keys(finals) } },
  ];
});

// 08 树洞漫聊：开会话 → 一轮 SSE → 收段 → 轮询
await scenario(8, 'companion', '漫聊开会话→一轮→收段→任务终态', async () => {
  const opened = await api('POST', '/companion/sessions', {}, access);
  const turnEvents = await sse(`/companion/sessions/${opened.sessionId}/turns`, {
    method: 'POST', body: { userText: '今天组会被临时挪到了周五，有点烦' }, token: access,
  });
  const turnDone = turnEvents.find((e) => e.event === 'turn_done');
  const end = await api('POST', `/companion/sessions/${opened.sessionId}/end`, {}, access);
  let taskStatus = 'NO_TASK';
  let finalAgents = [];
  if (end.taskNo) {
    const { task } = await waitTask(end.taskNo, access);
    taskStatus = task.status;
    finalAgents = Object.keys((await finalsOf(end.taskNo, access)).finals);
  }
  return [
    { name: 'open', response: { sessionId: '<redacted>', status: opened.status } },
    { name: 'turn', response: { events: turnEvents.length, turnDone: turnDone?.data ?? null } },
    { name: 'end', response: { taskNo: end.taskNo ? '<redacted>' : '' } },
    { name: 'task', response: { status: taskStatus, finalAgents } },
  ];
});

// 09 打卡 + 补签 + streak
await scenario(9, 'checkin', '打卡→补签→streak', async () => {
  const body = { emotion: 'JOY', rating: 4, energy: 4, note: '基线打卡：傍晚绕湖走了一圈' };
  const created = await api('POST', '/mood-check-ins', body, access);
  const makeupBody = { emotion: 'CALM', energy: 3, note: '基线补签' };
  const madeUp = await api('POST', '/mood-check-ins/makeup', makeupBody, access, { date: daysAgo(3) });
  const streak = await api('GET', '/mood-check-ins/streak', undefined, access);
  const month = await api('GET', '/mood-check-ins', undefined, access, { month: today().slice(0, 7) });
  return [
    { name: 'checkin', request: body, response: created },
    { name: 'makeup', request: { ...makeupBody, date: daysAgo(3) }, response: madeUp },
    { name: 'streak', response: streak },
    { name: 'month', response: month },
  ];
});

// 10 计划 active/detail（由疏导步骤生成；无计划时如实记录为空）
await scenario(10, 'plan', '计划卡片与详情', async () => {
  const active = await api('GET', '/plans/active', undefined, access);
  const steps = [{ name: 'active', response: active }];
  const planNo = Number(String(active.plan?.planId ?? '').replace(/^pl_/, ''));
  if (Number.isFinite(planNo) && planNo > 0) {
    steps.push({ name: 'detail', response: await api('GET', `/plans/${planNo}`, undefined, access) });
  } else {
    steps.push({ name: 'detail', response: null, note: '疏导未生成计划（如实记录）' });
  }
  return steps;
});

// 11 报告列表/详情/反馈
await scenario(11, 'report', '报告列表→详情→反馈→回读', async () => {
  const list = await api('GET', '/reports', undefined, access, { page: 0, size: 5 });
  const first = list.items?.[0];
  if (!first) throw new Error('报告列表为空，无法验证详情与反馈');
  const detail = await api('GET', `/reports/${first.id}`, undefined, access);
  const fb = await api('POST', `/reports/${first.id}/feedback`, { rating: 'USEFUL', note: '基线反馈' }, access);
  const after = await api('GET', `/reports/${first.id}`, undefined, access);
  return [
    { name: 'list', response: { total: list.total, items: list.items } },
    { name: 'detail', response: detail },
    { name: 'feedback', response: fb },
    { name: 'detailAfter', response: after },
  ];
});

// 12 档案汇总 + 导出 + 一次性领取
await scenario(12, 'archive', '档案汇总→导出→领取', async () => {
  const summary = await api('GET', '/archive/summary', undefined, access);
  const exp = await api('POST', '/archive/export', {}, access);
  const claimed = await api('GET', `/archive/export/${exp.fileId}`, undefined, access);
  return [
    { name: 'summary', response: summary },
    { name: 'export', response: { expiresInSeconds: exp.expiresInSeconds, fileId: '<redacted>' } },
    { name: 'claim', response: claimed },
  ];
});

// 13 注销申请 + 撤回（独立账号；申请即吊销全部令牌，撤回需冷静期内重新登录）
await scenario(13, 'account', '注销申请→重新登录→状态→撤回→恢复正常', async () => {
  const u = `base_del_${ts}`;
  const reg = await api('POST', '/auth/register', { username: u, password: PW, nickname: '基线注销' });
  const applied = await api('POST', '/users/me/delete', { password: PW }, reg.accessToken);
  const relogin = await api('POST', '/auth/login', { username: u, password: PW });
  const token = relogin.accessToken;
  const pending = await api('GET', '/auth/me', undefined, token);
  const cancelled = await api('POST', '/users/me/delete/cancel', {}, token);
  const after = await api('GET', '/auth/me', undefined, token);
  return [
    { name: 'apply', request: { password: '<redacted>' }, response: applied },
    { name: 'relogin', response: relogin },
    { name: 'pending', response: pending },
    { name: 'cancel', response: cancelled },
    { name: 'after', response: after },
  ];
});

// 14 管理端任务：列表/详情/重跑（无 FAILED 任务则如实 SKIPPED）
let adminToken = null;
try {
  adminToken = (await api('POST', '/auth/login', { username: 'admin', password: PW })).accessToken;
} catch (err) {
  skips.push('14-admin-task: admin 登录失败（' + err.message + '）');
}
if (!adminToken) {
  save('14-admin-task', { scenario: 'admin-task', note: '管理端任务', status: 'SKIPPED', reason: 'admin 账号不可登录或未提权' });
} else {
  await scenario(14, 'admin-task', '管理端任务列表/详情/失败任务重跑', async () => {
    const list = await api('GET', '/admin/tasks', undefined, adminToken, { page: 0, size: 10 });
    const target = list.items?.find((x) => x.taskNo === diaryTaskNo) ?? list.items?.[0];
    if (!target) throw new Error('管理端任务列表为空');
    const detail = await api('GET', `/admin/tasks/${target.taskNo}`, undefined, adminToken);
    const failed = (list.items || []).find((x) => x.status === 'FAILED');
    let retry;
    if (failed) {
      retry = { attempted: true, response: await api('POST', `/admin/tasks/${failed.taskNo}/retry`, undefined, adminToken) };
    } else {
      retry = { attempted: false, reason: '当前列表无 FAILED 任务，重跑接口留待有失败任务时验证' };
      skips.push('14-admin-task/retry: 无 FAILED 任务，未执行');
    }
    return [
      { name: 'list', response: { total: list.total, items: list.items } },
      { name: 'detail', response: detail },
      { name: 'retry', response: retry },
    ];
  });
}

// 15 群体看板抑制（ADMIN 建组 + 1 名成员 → 统计被抑制）
if (!adminToken) {
  save('15-board-suppression', { scenario: 'board-suppression', note: '群体看板抑制', status: 'SKIPPED', reason: 'admin 账号不可登录或未提权' });
  skips.push('15-board-suppression: admin 不可用');
} else {
  await scenario(15, 'board-suppression', '群体看板 <10 人抑制', async () => {
    const gname = `基线抑制组-${ts}`;
    const group = await api('POST', '/admin/board/groups', { name: gname }, adminToken);
    await api('POST', `/admin/board/groups/${group.id}/members`, { userIds: [mainUserId] }, adminToken);
    const stats = await api('GET', `/admin/board/groups/${group.id}/stats`, undefined, adminToken, { days: 7 });
    return [
      { name: 'create', request: { name: gname }, response: { id: '<redacted>' } },
      { name: 'members', request: { userIds: ['<redacted>'] }, response: { added: 1 } },
      { name: 'stats', response: stats },
    ];
  });
}

console.log(`\n完成：golden 写入 ${OUT}/`);
if (skips.length) console.log('SKIPPED:\n  - ' + skips.join('\n  - '));
if (failures.length) {
  console.error('FAILED:\n  - ' + failures.join('\n  - '));
  process.exit(1);
}
console.log('全部场景已执行。');
