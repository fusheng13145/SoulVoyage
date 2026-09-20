// M8 真机冒烟（中文接口链路）：N1 场景扩容+标签 / N3 每日一读+收藏 / N4 管理端热更新即时生效 /
// C3 中断续练+训练档案 / C5 任务历史分页。
// 前置：dev 后端起在 :8080；admin 角色需已在库中提权（见脚本内提示）。
// 用法：node deploy/tools/smoke_m8.mjs
const BASE = 'http://localhost:8080/api/v1';
let token = '';
let adminToken = '';

async function api(method, path, body, params, asAdmin) {
  const url = new URL(BASE + path);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const t = asAdmin ? adminToken : token;
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(t ? { Authorization: `Bearer ${t}` } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  return { res, json };
}

/** 最小 SSE 解析：收集 event/data 对直到流关闭（/simulations/{id}/turns） */
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

/** GET SSE 订阅（/tasks/{no}/stream，含历史重放） */
async function sseGet(path) {
  const res = await fetch(BASE + path, {
    headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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

// 等后端就绪（最多 60s）
for (let i = 0; ; i++) {
  try {
    const { res } = await api('POST', '/auth/login', { username: 'x', password: 'y' });
    if (res.status === 401 || res.status === 200) break;
  } catch { /* 还没起来 */ }
  if (i > 60) throw new Error('后端 60s 未就绪');
  await new Promise(r => setTimeout(r, 1000));
}

token = (await api('POST', '/auth/register',
  { username: 'smoke_m8_' + Date.now().toString(36), password: PW, nickname: 'M8冒烟' })).json.data.accessToken;
expect(token, '注册应返回 accessToken');

adminToken = (await api('POST', '/auth/login', { username: 'admin', password: PW })).json?.data?.accessToken
  || (await api('POST', '/auth/login', { username: 'smoke_m8_admin', password: PW })).json?.data?.accessToken
  || (await api('POST', '/auth/register', { username: 'smoke_m8_admin', password: PW, nickname: 'M8管理' })).json.data.accessToken;
expect(adminToken, '需要 admin 令牌：注册后请在 dev 库执行 UPDATE `user` SET role=\'ADMIN\' WHERE username=\'smoke_m8_admin\'; 再重跑');
ok('注册登录 + admin 令牌');

// ── N1 场景扩容：10 卡 + 标签/推荐位 ──
{
  const { json } = await api('GET', '/scenes');
  const scenes = json.data;
  expect(scenes.length === 10, `场景应为 10 张，实际 ${scenes.length}`);
  expect(scenes.every(s => Array.isArray(s.tags) && s.tags.length > 0), '每张卡都应有 tags');
  expect(scenes.every(s => Array.isArray(s.recommendedFor) && s.recommendedFor.length > 0), '每张卡都应有 recommendedFor');
  expect(scenes.some(s => s.code === 'TEACHER_EXTENSION'), '应含师生缓考场景');
  expect(scenes.some(s => s.code === 'SELF_CARE'), '应含自我关怀场景');
  ok(`场景卡 ${scenes.length} 张，标签/推荐位齐备`);
}

// ── N3 每日一读：同日同卡 + 收藏开关 ──
let psyCode = '';
{
  const a = (await api('GET', '/readings/today')).json.data;
  psyCode = a.kgNodeId;
  expect(psyCode.startsWith('psy_'), `今日一读应为 psy_*，实际 ${psyCode}`);
  expect(a.title && a.microAction, '卡片应含标题与微行动');
  expect(a.firstToday === true, '首次请求应标记 firstToday');
  const b = (await api('GET', '/readings/today')).json.data;
  expect(b.kgNodeId === psyCode && b.firstToday === false, '同日重复请求应返回同一篇');
  ok(`每日一读《${a.title}》同日重发同卡`);

  const on = (await api('POST', '/readings/favorites', { type: 'PSY_TOPIC', refCode: psyCode })).json.data;
  expect(on.favorited === true, '收藏应生效');
  const card = (await api('GET', '/readings/today')).json.data;
  expect(card.favorited === true, '卡片应反映收藏态');
  const bad = await api('POST', '/readings/favorites', { type: 'PSY_TOPIC', refCode: 'psy_not_exist' });
  expect(bad.json.code !== 0, '收藏不存在的篇目应被拒');
  ok('收藏开关 + 非法引用拒绝');
}

// ── N4 管理端热更新：PUT 后读路径即时可见 ──
{
  const denied = await api('PUT', '/admin/content/kg/psy_smoke_probe',
    { type: 'PSY_TOPIC', name: '冒烟探针', status: 1,
      payload: { summary: '冒烟用摘要', microAction: '无', aboutTags: ['其他'], readingSec: 30 } });
  expect(denied.res.status === 403, '普通用户不应能写内容管理端');

  const code = 'psy_smoke_' + Date.now().toString(36);
  const put = await api('PUT', '/admin/content/kg/' + code,
    { type: 'PSY_TOPIC', name: '热更新冒烟篇', status: 1,
      payload: { summary: '管理端即时生效验证', microAction: '读完说出关键词', aboutTags: ['其他'], readingSec: 30 } },
    null, true);
  expect(put.json.code === 0 && put.json.data.effective === true, '管理端写入应成功: ' + JSON.stringify(put.json));
  const v1 = put.json.data.contentVersion;

  // 抬版本后 5s TTL 内 bump 即时失效——新用户今日一读若命中属随机，改为直接读管理端列表断言
  const list = await api('GET', '/admin/content/kg', null, { type: 'PSY_TOPIC' }, true);
  expect(list.json.data.some(r => r.code === code), '管理端列表应含新篇');
  const refresh = (await api('POST', '/admin/content/refresh', null, null, true)).json.data;
  expect(refresh.contentVersion > v1, '手动 refresh 应再抬版本');
  const off = await api('PUT', '/admin/content/kg/' + code, { status: 2 }, null, true);
  expect(off.json.data.contentVersion > v1, '下架也应 bump 版本');
  ok(`热更新链路：新增→列表可见→refresh→下架（contentVersion ${v1}→${off.json.data.contentVersion}）`);
}

// ── C3 中断/续练 + 训练档案 ──
{
  const opened = (await api('POST', '/simulations', { sceneCode: 'DORM_CONFLICT', difficulty: 'NORMAL' })).json.data;
  expect(opened.simulateId, '应能开场');
  const ev1 = await sse(`/simulations/${opened.simulateId}/turns`, { userText: '我想跟你商量熄灯后的安排' });
  expect(ev1.some(e => e.event === 'turn_done'), '首轮 SSE 应正常收口');
  const intr = (await api('POST', `/simulations/${opened.simulateId}/interrupt`)).json.data;
  expect(intr.status === 'INTERRUPTED', '退出应留档 INTERRUPTED');
  const listI = (await api('GET', '/simulations', null, { status: 'INTERRUPTED' })).json.data;
  expect(listI.items.some(x => x.simulateId === opened.simulateId), '中断列表应能捞出该会话');
  const ev2 = await sse(`/simulations/${opened.simulateId}/turns`, { userText: '上次说到一半，今天继续' });
  const done2 = ev2.find(e => e.event === 'turn_done');
  expect(done2 && done2.data.turnNo === 2, '续练应能从第 2 轮继续');
  ok('C3 退出留档 → 列表捞出 → 续练回到第 2 轮');

  // 走一遍复盘，训练档案应回填分数与雷达；报告正文应含闭集校验过的参考案例
  const fin = (await api('POST', `/simulations/${opened.simulateId}/finish`)).json.data;
  let finished = null;
  for (let i = 0; i < 100; i++) {
    finished = (await api('GET', `/tasks/${fin.taskNo}`)).json.data;
    if (finished.status === 'SUCCESS' || finished.status === 'FAILED' || finished.status === 'PARTIAL_SUCCESS') break;
    await new Promise(r => setTimeout(r, 200));
  }
  expect(finished.status === 'SUCCESS', '复盘任务应成功: ' + finished.status);
  const evs = await sseGet(`/tasks/${fin.taskNo}/stream`);   // 订阅含历史重放
  const mid = evs.find(e => e.event === 'middle_result' && e.data.agent === 'SIMULATE');
  expect(mid && mid.data.payload.referenceCaseDetail
    && mid.data.payload.referenceCaseDetail.kgNodeId.startsWith('cc_'),
    'SIMULATE 中间结果应含 referenceCaseDetail（cc_* 闭集内案例）');
  const rp = (await api('GET', '/reports', null, { page: 0, size: 5 })).json.data;
  const simRp = rp.items.find(x => x.type === 'SIMULATE');
  expect(simRp, '应生成 SIMULATE 复盘报告');
  const rpd = (await api('GET', `/reports/${simRp.id}`)).json.data;
  expect(String(rpd.content.referenceCase).startsWith('cc_'), '报告正文应含闭集校验过的 referenceCase');
  const stats = (await api('GET', '/simulations/stats')).json.data;
  const dorm = stats.find(x => x.sceneCode === 'DORM_CONFLICT');
  expect(dorm && dorm.times >= 1 && dorm.bestScore != null, 'stats 应含场景均分/最佳分');
  expect(dorm.lastDimensions && Object.keys(dorm.lastDimensions).length > 0, 'stats 应含上次四维雷达');
  ok(`C3 复盘→档案：${dorm.sceneTitle} 练习 ${dorm.times} 次 · 最佳 ${dorm.bestScore} 分 · 报告引用案例 ${rpd.content.referenceCase}`);
}

// ── C5 任务历史分页 ──
{
  const all = (await api('GET', '/tasks', null, { page: 0, size: 5 })).json.data;
  expect(Array.isArray(all.items) && all.total >= 1, '任务列表应可分页');
  expect(all.size === 5, 'size 参数应生效');
  const item = all.items[0];
  expect(item.taskNo && item.pipelineCode && item.status, '列表项应含元信息');
  const filtered = (await api('GET', '/tasks', null, { pipelineCode: 'SIMULATE_PIPELINE' })).json.data;
  expect(filtered.items.every(x => x.pipelineCode === 'SIMULATE_PIPELINE'), '过滤应生效');
  ok(`任务历史 total=${all.total}（SIMULATE 过滤后 ${filtered.total}）`);
}

// ── N3 收藏进档案（S2 可携带权数据导出） ──
{
  const exp = (await api('GET', '/users/me/data-export')).json.data;
  const dl = (await api('POST', `/users/me/data-export/${exp.fileId}`)).json.data;
  expect(Array.isArray(dl.favorites) && dl.favorites.some(f => f.refCode === psyCode),
    '数据导出应含今日收藏的科普篇目');
  const off = (await api('POST', '/readings/favorites', { type: 'PSY_TOPIC', refCode: psyCode })).json.data;
  expect(off.favorited === false, '再点一次应取消收藏');
  ok('收藏随 S2 数据导出 + 取消回收');
}

console.log('\nM8 内容与训练生态冒烟全部通过 ✅');
