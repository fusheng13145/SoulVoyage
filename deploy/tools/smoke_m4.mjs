// M4 真机冒烟：四步日记流水线（含危机双轨+关怀模式）→ 转介资源 → 练习打卡 → 轨迹/画像 → 限时导出 → 模拟复盘风险尾巴
// 用法：node deploy/tools/smoke_m4.mjs
const BASE = 'http://localhost:8080/api/v1';
let token = '';

async function api(method, path, body, bearer = true) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(bearer && token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json();
  if (json.code !== 0) throw Object.assign(new Error(`${method} ${path} -> ${json.code} ${json.msg}`), { code: json.code });
  return json.data;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function runTask(pipelineCode, payload) {
  const { taskNo } = await api('POST', '/tasks', { pipelineCode, payload, clientReqId: `smoke-${Date.now()}` });
  for (let i = 0; i < 60; i++) {
    const t = await api('GET', `/tasks/${taskNo}`);
    if (['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(t.status)) return t;
    await sleep(500);
  }
  throw new Error('task timeout ' + taskNo);
}

const assert = (cond, msg) => { if (!cond) throw new Error('ASSERT FAILED: ' + msg); };

// ① 普通日记：情绪→溯源→疏导→风险归档 四步，风险应为 LOW、不建风险事件
const username = 'smoke_m4_' + Date.now().toString(36).slice(-6);
console.log('== register', username);
token = (await api('POST', '/auth/register',
  { username, password: 'Passw0rd!2026', nickname: 'M4冒烟' })).accessToken;

let t = await runTask('DIARY_PIPELINE', {
  diaryText: '今天要交三份报告，还有小组作业没推进，一想到就做不完我就特别焦虑，心脏怦怦跳。',
  recordDate: new Date().toISOString().slice(0, 10),
});
console.log(`== diary1 ${t.status}`);
assert(t.status === 'SUCCESS', '普通日记链路 SUCCESS');
assert(t.payload.riskLevel === 'LOW', '普通日记风险 LOW，实际 ' + t.payload.riskLevel);
assert(t.payload.profileUpdated === true, '周画像已更新');
assert(t.payload.encryption.algo === 'AES-256-GCM', '加密回执');

const reports = await api('GET', '/reports?page=0&size=30');
const supportRep = reports.items.find((r) => r.type === 'SUPPORT');
assert(supportRep, '存在 SUPPORT 自助方案报告');
const support = await api('GET', `/reports/${supportRep.id}`);
console.log(`== support plan: ${support.content.planTitle}`,
  `${support.content.matchedExercises.map((m) => m.exerciseId).join(',')} disclaimer=${support.content.disclaimer}`);
assert(support.content.matchedExercises.every((m) => /^ex_/.test(m.exerciseId)), '练习闭环集合');
assert(support.content.disclaimer === true, '免责声明常量');

// ② 危机日记：规则轨一票升级 → HIGH + 转介；再次登录态进关怀模式，二跑不再出 SUPPORT
t = await runTask('DIARY_PIPELINE', {
  diaryText: '这段时间什么都提不起劲，活着真没意思，我消失了大概也不会有人在意吧。',
  recordDate: new Date().toISOString().slice(0, 10),
});
assert(t.status === 'SUCCESS', '危机日记链路完成');
assert(t.payload.riskLevel === 'HIGH', '危机日记风险 HIGH，实际 ' + t.payload.riskLevel);
assert(t.payload.referral?.show === true, 'HIGH 回执带转介卡');
console.log(`== crisis diary risk=HIGH referral="${t.payload.referral.headline}"`);

const prof = await api('GET', '/emotions/profile');
assert(prof.crisisMode === true, '已进入关怀模式');
const supportBefore = (await api('GET', '/reports?page=0&size=50')).items.filter((r) => r.type === 'SUPPORT').length;
t = await runTask('DIARY_PIPELINE', {
  diaryText: '还是有点难受，但想试试把作业拆开一小步一小步做。',
  recordDate: new Date().toISOString().slice(0, 10),
});
const supportAfter = (await api('GET', '/reports?page=0&size=50')).items.filter((r) => r.type === 'SUPPORT').length;
assert(supportAfter === supportBefore, `关怀模式跳过疏导步（${supportBefore}→${supportAfter}）`);
assert(t.payload.riskLevel === 'HIGH' || t.payload.riskLevel === 'MEDIUM' || t.payload.riskLevel === 'LOW', '关怀模式仍收口于风险归档');
console.log(`== crisis-mode bypass ok, second-run risk=${t.payload.riskLevel}`);

// ③ 转介资源公开可读
const res = await fetch(BASE + '/risk/resources');
const rr = await res.json();
assert(rr.code === 0 && rr.data.resources.length >= 4, '转介资源接口公开');
console.log('== referral resources:', rr.data.resources.map((x) => `${x.name}:${x.value}`).join(' | '));

// ④ 练习目录 + 打卡
const exList = await api('GET', '/exercises');
assert(exList.length === 5, '练习库 5 项');
await api('POST', '/exercise-records', { exerciseId: 'ex_54321', completed: true });
await api('POST', '/exercise-records', { exerciseId: 'ex_478_breath', completed: false });
const recs = await api('GET', '/exercise-records?limit=10');
assert(recs.length === 2, '打卡记录 2 条');
try { await api('POST', '/exercise-records', { exerciseId: 'ex_hack', completed: true }); throw new Error('closed-set 失守'); }
catch (e) { assert(e.code === 2001, '闭环集合外 exerciseId 被拒: ' + e.code); }
console.log('== exercise checkin ok, closed-set enforced');

// ⑤ 轨迹 + 周报
const traj = await api('GET', '/emotions/trajectory');
assert(traj.points.length >= 3, `轨迹点 ≥3，实际 ${traj.points.length}`);
assert(/^\d{4}-W\d{2}$/.test(traj.from) === false && traj.points[0].date, '轨迹返回日期');
const wk = await api('GET', '/archive/summary');
console.log(`== trajectory ${traj.points.length} pts; week ${wk.statWeek} risk=${wk.profile?.riskLevel}`,
  `stressorTop=${(wk.profile?.stressorTop ?? []).map((s) => s.name).join('/')}`);
assert(/^\d{4}-W\d{2}$/.test(wk.statWeek), 'ISO 周编号');
assert(wk.emotionPoints.length >= 3, '周报情绪点');

// ⑥ 限时一次性导出
const { fileId, expiresInSeconds } = await api('POST', '/archive/export', {});
assert(expiresInSeconds === 300, '导出 TTL 5 分钟');
const snap = await api('GET', `/archive/export/${fileId}`);
console.log(`== export snapshot: reports=${snap.reports.length} pts=${snap.emotionPoints.length} weeks=${snap.profiles.length}`);
assert(snap.reports.length >= 3 && snap.profiles.length >= 1, '快照内容齐全');
try { await api('GET', `/archive/export/${fileId}`); throw new Error('导出链接可重放！'); }
catch (e) { assert(e.code === 40400 || /不存在|已/.test(e.message), '一次性：二次领取被拒 ' + e.message); }
console.log('== one-time export enforced');

// ⑦ 人际模拟尾巴：复盘后由 RISK_ARCHIVE 收口
const opened = await api('POST', '/simulations', { sceneCode: 'GROUP_PROJECT', difficulty: 'NORMAL' });
const resTurn = await fetch(`${BASE}/simulations/${opened.simulateId}/turns`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ userText: '我注意到这次任务里我的部分提前交了，但你的部分晚了两天，我感到很着急，我们能不能约定一个中间检查点' }),
});
const txt = await resTurn.text();
assert(txt.includes('event:turn_done'), '模拟训练一轮完成');

const fin = await api('POST', `/simulations/${opened.simulateId}/finish`, {});
let ft;
for (let i = 0; i < 60; i++) {
  ft = await api('GET', `/tasks/${fin.taskNo}`);
  if (['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(ft.status)) break;
  await sleep(500);
}
assert(ft.status === 'SUCCESS', '模拟复盘链路 SUCCESS');
assert(ft.payload.riskLevel === 'LOW', '模拟尾巴风险 LOW（无危机台词），实际 ' + ft.payload.riskLevel);
assert(ft.payload.profileUpdated === true, '周画像含训练维度更新');
console.log(`== simulate finish -> risk tail ${ft.payload.riskLevel}, receipt keys=${Object.keys(ft.payload).join(',')}`);

console.log('SMOKE_M4_OK user=' + username + ' export=' + fileId);
