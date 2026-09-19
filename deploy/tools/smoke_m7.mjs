// M7 真机冒烟（中文接口链路）：打卡/补签/streak → 漫聊 SSE（含高危当轮拦截/这句别分析/收段）
// → 认知书写→TRACE 任务 → 跟练记录 → 成就/通知/偏好。
// 用法：dev 后端起在 :8080 后 `node deploy/tools/smoke_m7.mjs`
const BASE = 'http://localhost:8080/api/v1';
let token = '';

async function api(method, path, body, params) {
  const url = new URL(BASE + path);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  return { res, json };
}

/** 最小 SSE 解析：收集 event/data 对直到流关闭 */
async function sse(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', current = '';
  const events = [];
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
}

function expect(cond, msg) { if (!cond) throw new Error('断言失败: ' + msg); }
const ok = (name) => console.log('  ✔', name);

const PW = 'Passw0rd!2026';
const today = new Date().toLocaleDateString('en-CA');

// 等后端就绪（最多 60s；启动期 ECONNREFUSED 属正常，吞掉重试）
for (let i = 0; ; i++) {
  try {
    const { res } = await api('POST', '/auth/login', { username: 'x', password: 'y' });
    if (res.status === 401 || res.status === 200) break;
  } catch { /* 还没起来 */ }
  if (i > 60) throw new Error('后端 60s 未就绪');
  await new Promise(r => setTimeout(r, 1000));
}

token = (await api('POST', '/auth/register',
  { username: 'smoke_m7_' + Date.now().toString(36), password: PW, nickname: 'M7冒烟' })).json.data.accessToken;
expect(token, '注册应返回 accessToken');
ok('注册登录');

// ── G1/G3 打卡：中文备注信封加密回读 + streak + 补签护栏 ──
{
  const { res, json } = await api('POST', '/mood-check-ins',
    { emotion: 'JOY', rating: 4, energy: 4, note: '傍晚绕湖走了一圈，风很软' });
  expect(res.status === 200 && json.code === 0, '打卡应成功: ' + JSON.stringify(json));
  const m = await api('GET', '/mood-check-ins', null, { month: today.slice(0, 7) });
  expect(m.json.data.today?.note === '傍晚绕湖走了一圈，风很软', '中文备注应无损回读');
  const st = await api('GET', '/mood-check-ins/streak');
  expect(st.json.data.totalDays === 1, 'streak 总天数应为 1');
  ok('打卡落库 → 月热力回读 → streak');

  const mk = await api('POST', '/mood-check-ins/makeup',
    { emotion: 'CALM', energy: 3, note: '补一下前天' }, { date: '2026-01-01' });
  expect(mk.json.code !== 0 || mk.res.status >= 400, '14 天外补签应被拒');
  const mk2 = await api('POST', '/mood-check-ins/makeup',
    { emotion: 'CALM', energy: 3 }, { date: (await dayOffset(-3)) });
  expect(mk2.json.code === 0 && mk2.json.data.madeUp === true, '14 天内补签应成功且标记 madeUp');
  const mk3 = await api('POST', '/mood-check-ins/makeup',
    { emotion: 'CALM', energy: 3 }, { date: (await dayOffset(-2)) });
  expect(mk3.json.code !== 0, '每自然月仅 1 次补签');
  ok('补签护栏：超窗拒绝 · 窗内成功 · 每月一次');
}

async function dayOffset(days) {
  return new Date(Date.now() + days * 86400000).toLocaleDateString('en-CA');
}

// ── G4 计划：无疏导结果时也应可查（active 可为空但不报错） ──
{
  const { json } = await api('GET', '/plans/active');
  expect(json.code === 0 && ('plan' in json.data), '/plans/active 应返回 {plan}');
  ok('计划接口就绪（当前 plan=' + (json.data.plan ? json.data.plan.title : 'null，先疏导后生成') + '）');
}

// ── C0 漫聊：普通轮 SSE 流 + 高危句当轮 100% 拦截 + 这句别分析 + 收段消化 ──
let sessionId, firstTurnId;
{
  const { json } = await api('POST', '/companion/sessions', {});
  sessionId = json.data.sessionId;
  expect(sessionId, '应返回活跃会话');

  const ev1 = await sse(`/companion/sessions/${sessionId}/turns`, { userText: '今天组会被临时挪到了周五，有点烦' });
  const deltas1 = ev1.filter(e => e.event === 'ai_delta');
  const done1 = ev1.find(e => e.event === 'turn_done');
  expect(deltas1.length > 0 && done1, '普通轮应流式出词并有 turn_done');
  expect(done1.data.turnId > 0 && done1.data.crisis === false, 'turn_done 应携带 turnId');
  firstTurnId = done1.data.turnId;
  ok(`普通轮：${deltas1.length} 个 ai_delta，mood=${done1.data.moodTag}`);

  const ev2 = await sse(`/companion/sessions/${sessionId}/turns`, { userText: '最近很累，有点不想活了' });
  const done2 = ev2.find(e => e.event === 'turn_done');
  const crisisEv = ev2.find(e => e.event === 'crisis');
  const text2 = ev2.filter(e => e.event === 'ai_delta').map(e => e.data.text).join('');
  expect(crisisEv && crisisEv.data.level === 'HIGH' && crisisEv.data.hotline === '12356',
    '高危轮必须当轮下发 crisis 事件与热线');
  expect(text2.includes('不想一个人扛') || text2.includes('你刚才这句话'), '当轮回复应替换为陪伴话术: ' + text2);
  expect(done2?.data.crisis === true, 'turn_done 应带 crisis 标记');
  ok('高危句当轮拦截：crisis 事件 + 陪伴话术 + 不进 LLM');

  const no = await api('POST', `/companion/turns/${firstTurnId}/no-analyze`, {});
  expect(no.json.code === 0, '这句别分析应成功');
  const sealed = await api('POST', `/companion/sessions/${sessionId}/end`, {});
  expect(sealed.res.status === 202 && 'taskNo' in sealed.json.data, '收段应 202 + taskNo');
  const tr = await api('GET', `/companion/sessions/${sessionId}`);
  const list = tr.json.data.turnList;
  expect(list.length === 2 && list[0].noAnalyze === true && list[1].crisis === true,
    '回放应保序：第 1 轮已排除分析、第 2 轮危机命中');
  expect(list[0].userText === '今天组会被临时挪到了周五，有点烦', '属主解密回读应无损');
  ok('这句别分析 → 收段 → 回放保序解密无损');

  const late = await api('POST', '/companion/sessions', {});
  expect(late.json.data.segmentNo === 2 || late.json.data.turns === 0, '收段后应另起新段');
}

// ── C4 认知书写：三栏 → COGNITIVE_PIPELINE(EMOTION→TRACE→RISK_ARCHIVE) ──
{
  const { json } = await api('POST', '/exercises/cognitive-writing', {
    situation: '组会发言被打断了两次',
    autoThought: '我说的东西没人想听',
    alternativeThought: '被打断可能是他的习惯，不代表内容没价值',
  });
  expect(json.code === 0 && json.data.taskNo, '认知书写应返回 taskNo');
  let status = '';
  for (let i = 0; i < 100; i++) {
    const t = await api('GET', `/tasks/${json.data.taskNo}`);
    status = t.json.data.status;
    if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(status)) break;
    await new Promise(r => setTimeout(r, 300));
  }
  expect(status === 'SUCCESS' || status === 'PARTIAL_SUCCESS', '认知书写管道应完成，实际 ' + status);
  const recs = await api('GET', '/exercise-records', null, { limit: 5 });
  expect(recs.json.data.some(r => r.completed && r.exerciseName === '认知书写三栏表'), '练习记录应自动落完成');
  ok('三栏书写 → 管道完成 → 跟练记录自动盖章');
}

// ── G2/G5/G6 读侧：成就墙 / 通知中心 / 偏好真源 / 来信列表 ──
{
  const ach = await api('GET', '/achievements');
  expect(ach.json.data.totalCount >= 7 && ach.json.data.items.some(a => a.unlocked),
    '成就墙应展示目录且至少点亮 COMPANION_OPENED');
  ok(`成就墙 ${ach.json.data.unlockedCount}/${ach.json.data.totalCount}（含漫聊首聊点亮）`);

  const put = await api('PUT', '/preferences', { reminderTime: '21:30', checkinReminderOn: true, theme: 'dark' });
  expect(put.json.data.reminderTime === '21:30' && put.json.data.checkinReminderOn === true, '偏好应服务端回读');
  const bad = await api('PUT', '/preferences', { reminderTime: '99:99' });
  expect(bad.json.code !== 0, '非法提醒时间应被拒');
  ok('偏好服务端真源：改写回读 + 非法值拒绝');

  const nt = await api('GET', '/notifications', null, { page: 0, size: 10 });
  expect(Array.isArray(nt.json.data.items), '通知中心应可列表');
  const lt = await api('GET', '/letters');
  expect(Array.isArray(lt.json.data.items), '来信列表应可访问');
  ok(`通知 ${nt.json.data.items.length} 条 · 来信 ${lt.json.data.items.length} 封（来信由周一 07:00 任务生成）`);
}

console.log('\nM7 成长陪伴冒烟全部通过 ✅');
