// M12 冒烟（dev 真机 MySQL + Redis）：辅导员群体看板——三道隐私闸走真接口：
//   权限（@perms 表闸门）、授权默认关（无偏好行=不参与）、<10 人整块抑制 + 单日贡献者下限。
// 前置：dev 后端起在 :8080（SV_LLM_PROVIDER=mock 即可，看板不碰模型）；
//   已执行 deploy/sql/migrate_m12.sql；库中有 role='ADMIN' 的 admin 账号（同 smoke_m9 提示）。
// 中文一律走 fetch（Git Bash 的 curl 会把中文编成 GBK 字节，后端只会回 400/2001）。

const BASE = 'http://localhost:8080/api/v1';
const PW = 'Passw0rd!2026';
const ADMIN = { username: 'admin', password: PW };
const SECRET_NOTE = '这段备注是密文，看板永远不许读到它';

let pass = 0;
const fails = [];
const ok = (name, cond, detail = '') => {
  if (cond) {
    pass++;
    console.log(`  ✓ ${name}${detail ? '  ' + detail : ''}`);
  } else {
    fails.push(name + (detail ? ' — ' + detail : ''));
    console.log(`  ✗ ${name}${detail ? '  ' + detail : ''}`);
  }
};

async function req(method, p, body, token) {
  const res = await fetch(BASE + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = {};
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON 响应留给断言自己判 */
  }
  return { status: res.status, code: json.code, msg: json.msg, data: json.data, text };
}
const api = async (m, p, b, t) => {
  const r = await req(m, p, b, t);
  if (r.code !== 0) throw new Error(`${m} ${p} -> ${r.code} ${r.msg}`);
  return r.data;
};

async function newUser(i) {
  const username = `m12_${Date.now().toString(36).slice(-5)}_${i}`;
  const d = await api('POST', '/auth/register', { username, password: PW, nickname: '看板冒烟' + i });
  const me = await api('GET', '/auth/me', undefined, d.accessToken);
  return { username, token: d.accessToken, userId: me.userId };
}

const adminTk = (await api('POST', '/auth/login', ADMIN)).accessToken;
ok('admin 登录取得管理令牌', !!adminTk);

// —— 闸门 0：权限 —— 匿名 401 / 普通用户 403（未过 @perms 与角色）
{
  const anon = await req('GET', '/admin/board/groups');
  ok('匿名打看板 → 401', anon.status === 401, String(anon.status));
  const u = await newUser(0);
  const asUser = await req('GET', '/admin/board/groups', undefined, u.token);
  ok('普通用户打看板 → 403', asUser.status === 403, String(asUser.status));
}

// —— 授权默认关：新注册用户 counselorBoardOn 为 false，且能显式打开 ——
const members = [];
for (let i = 1; i <= 12; i++) members.push(await newUser(i));
{
  const prefs = await api('GET', '/preferences', undefined, members[0].token);
  ok('新账户默认不参与群体统计', prefs.counselorBoardOn === false);
  await api('PUT', '/preferences', { counselorBoardOn: true }, members[0].token);
  const after = await api('GET', '/preferences', undefined, members[0].token);
  ok('显式开启后偏好回读为 true', after.counselorBoardOn === true);
  await api('PUT', '/preferences', { counselorBoardOn: false }, members[0].token); // 收回，交给后面的批量授权
}

// —— 群体维护：空名/重名校验，成员重复添加幂等 ——
const stamp = Date.now().toString(36).slice(-6);
const gname = `冒烟群体_${stamp}`;
{
  const bad = await req('POST', '/admin/board/groups', { name: '   ' }, adminTk);
  ok('空群体名被拒', bad.code !== 0, bad.msg);
  const g = await api('POST', '/admin/board/groups', { name: gname }, adminTk);
  ok('创建群体成功', g.id > 0 && g.name === gname, `#${g.id}`);
  const dup = await api(
    'POST',
    `/admin/board/groups/${g.id}/members`,
    { userIds: [...members.map(m => m.userId), members[0].userId] },
    adminTk,
  );
  ok('重复成员幂等加入', dup.memberCount === 12, `memberCount=${dup.memberCount}`);
  const ghost = await req('POST', `/admin/board/groups/${g.id}/members`, { userIds: [999999999] }, adminTk);
  ok('不存在的用户加入被拒', ghost.code !== 0, ghost.msg);
  globalThis.GID = g.id;
}
const GID = globalThis.GID;

// —— 闸门 1：授权不足 10 人 → 整块抑制，响应里没有任何指标键 ——
{
  const s0 = await api('GET', `/admin/board/groups/${GID}/stats?days=7`, undefined, adminTk);
  ok('0 人授权 → 抑制', s0.suppressed === true && s0.consentedCount === 0);
  ok('抑制响应只带结论不带指标', !('checkIns' in s0) && !('valenceByDay' in s0) && !('riskEvents' in s0));
  for (let i = 0; i < 9; i++) await api('PUT', '/preferences', { counselorBoardOn: true }, members[i].token);
  const s9 = await api('GET', `/admin/board/groups/${GID}/stats?days=7`, undefined, adminTk);
  ok('9 人授权仍抑制（阈值 10）', s9.suppressed === true && s9.consentedCount === 9);
  ok('抑制原因写明防个体反推', String(s9.reason).includes('防个体反推'), s9.reason);
  await api('PUT', '/preferences', { counselorBoardOn: true }, members[9].token); // 第 10 人
  const list = await api('GET', '/admin/board/groups', undefined, adminTk);
  const row = list.find(x => x.id === GID);
  ok('列表页同步反映授权数', row.consentedCount === 10 && row.suppressed === false);
}

// —— 第 10 人授权后解锁：只聚合授权者的明文列 ——
{
  for (let i = 0; i < 10; i++) {
    await api('POST', '/mood-check-ins', { emotion: 'CALM', rating: 4, energy: 3, note: SECRET_NOTE }, members[i].token);
  }
  // 未授权的两人故意打卡：他们的数据必须不进任何统计
  for (let i = 10; i < 12; i++) {
    await api('POST', '/mood-check-ins', { emotion: 'ANGER', rating: 1, energy: 1, note: SECRET_NOTE }, members[i].token);
  }
  const s = await api('GET', `/admin/board/groups/${GID}/stats?days=7`, undefined, adminTk);
  ok('10 人授权 → 解除抑制', s.suppressed === false);
  ok('贡献者只数授权者', s.checkIns.contributors === 10, `contributors=${s.checkIns.contributors}`);
  ok('均值由授权者打卡算出', s.checkIns.avgRating === 4, `avgRating=${s.checkIns.avgRating}`);
  const codes = s.checkIns.emotions.map(e => e.code);
  ok('未授权者的情绪不进分布', codes.includes('CALM') && !codes.includes('ANGER'), codes.join(','));
  ok('当日贡献者达标不再藏日', s.valenceByDay.length >= 1 && s.hiddenDays === 0);
  ok('效价曲线只回日均值', s.valenceByDay.every(d => typeof d.date === 'string' && typeof d.avgValence === 'number'));
  const t = JSON.stringify(s);
  ok('响应不含打卡原文', !t.includes(SECRET_NOTE));
  ok('响应不含成员用户名', !members.some(m => t.includes(m.username)));
  ok('响应不含个体 id 字段', !t.includes('"userId"'));
}

// —— 移除一名授权成员 → 阈值回落，看板重新整块抑制 ——
{
  await api('DELETE', `/admin/board/groups/${GID}/members/${members[0].userId}`, undefined, adminTk);
  const s = await api('GET', `/admin/board/groups/${GID}/stats?days=7`, undefined, adminTk);
  ok('移除后 9 人授权重新抑制', s.suppressed === true && s.consentedCount === 9);
  const detail = await api('GET', `/admin/board/groups/${GID}`, undefined, adminTk);
  ok('移除即不再出现在成员名单', detail.memberCount === 11 && !detail.members.some(m => m.userId === members[0].userId));
}

// —— 窗口口径与错误路径 ——
{
  const clamp = await api('GET', `/admin/board/groups/${GID}/stats?days=999`, undefined, adminTk);
  ok('days 超界钳到 90', clamp.windowDays === 90, `windowDays=${clamp.windowDays}`);
  const missing = await req('GET', '/admin/board/groups/999999999', undefined, adminTk);
  ok('不存在的群体 → 业务错误非 500', missing.code !== 0 && missing.status !== 500, `${missing.status}/${missing.code}`);
}

console.log(`\n${fails.length ? 'FAILED' : 'ALL PASS'}: ${pass}/${pass + fails.length}`);
for (const f of fails) console.log('  ! ' + f);
process.exit(fails.length ? 1 : 0);
