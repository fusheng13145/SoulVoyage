// M6 真机冒烟：轻量打卡 POST /emotions/self-rating → SELF_RATING 轨迹可查 + 参数校验
// 用法：后端起在 :8080 后 `node deploy/tools/smoke_m6.mjs`
const BASE = 'http://localhost:8080/api/v1';
let token = '';

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  return { res, json };
}

function expect(cond, msg) { if (!cond) throw new Error('断言失败: ' + msg); }
const ok = (name) => console.log('  ✔', name);

const PW = 'Passw0rd!2026';

// 等后端就绪（最多 60s）
for (let i = 0; ; i++) {
  const { res } = await api('POST', '/auth/login', { username: 'x', password: 'y' });
  if (res.status === 401 || res.status === 200) break;
  if (i > 60) throw new Error('后端 60s 未就绪');
  await new Promise(r => setTimeout(r, 1000));
}

token = (await api('POST', '/auth/register',
  { username: 'smoke_m6_' + Date.now().toString(36), password: PW, nickname: 'M6冒烟' })).json.data.accessToken;
expect(token, '注册应返回 accessToken');
ok('注册登录');

const today = new Date().toLocaleDateString('en-CA');

// 未登录 401
{
  const saved = token; token = '';
  const { res } = await api('POST', '/emotions/self-rating', { emotion: '平静', valence: 0.4, intensity: 0.6 });
  expect(res.status === 401, '未登录打卡应 401，实际 ' + res.status);
  token = saved;
  ok('未登录打卡 → 401');
}

// 正常打卡（中文备注）
{
  const { res, json } = await api('POST', '/emotions/self-rating',
    { emotion: '平静', valence: 0.4, intensity: 0.68, note: '午后晒了太阳，感觉不错', date: today });
  expect(res.status === 200 && json.code === 0, '打卡应成功: ' + JSON.stringify(json));
  expect(json.data.date === today, '回显日期应为今天');
  ok(`打卡落库 id=${json.data.id}`);
}

// 轨迹可查到 SELF_RATING 点
{
  const { json } = await api('GET', `/emotions/trajectory?from=${today}&to=${today}`);
  const pts = json.data.points.filter(p => p.sourceType === 'SELF_RATING');
  expect(pts.length === 1, '当日应恰有 1 个 SELF_RATING 点，实际 ' + pts.length);
  expect(pts[0].emotion === '平静' && pts[0].valence === 0.400, '情绪/效价应回读一致');
  expect(JSON.stringify(pts[0].eventTags).includes('午后晒了太阳'), '备注应随 eventTags 回读');
  ok('轨迹回读：情绪/效价/中文备注无损');
}

// 参数校验：valence/intensity 越界、空情绪词、未来日期
for (const [label, body] of [
  ['valence 越界', { emotion: '开心', valence: 1.5, intensity: 0.5 }],
  ['intensity 越界', { emotion: '开心', valence: 0, intensity: 1.2 }],
  ['emotion 为空', { emotion: '  ', valence: 0, intensity: 0.5 }],
  ['未来日期', { emotion: '开心', valence: 0, intensity: 0.5, date: '2099-01-01' }],
]) {
  const { res, json } = await api('POST', '/emotions/self-rating', body);
  expect(res.status >= 400 || json.code !== 0, `${label} 应被拒`);
}
ok('四类非法入参全部拒绝');

console.log('\nM6 打卡冒烟全部通过 ✅');
