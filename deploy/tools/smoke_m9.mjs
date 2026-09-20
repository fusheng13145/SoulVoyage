// M9 真机冒烟（管理端 + 生产化，中文接口链路）：
// 角色守卫 / O3 指标总览 / A1 任务监控+重跑闸门 / A2 风险复核 SOP（二次授权拒绝→解密→结案联动 S1）/
// A3 内容预览试跑+热更新 / A4 用户支持（搜索·冻结断会话·解冻·危机看板）/ A5 审计链校验+密钥看板+导出记录。
// 前置：dev 后端起在 :8080，且库中有 role='ADMIN' 的 admin 账号（见脚本内提示）。
// 用法：node deploy/tools/smoke_m9.mjs
const BASE = (process.argv[2] || 'http://localhost:8080') + '/api/v1';
const PW = 'Passw0rd!2026';
let userToken = '';
let adminToken = '';

async function api(method, path, body, params, asAdmin = true) {
  const url = new URL(BASE + path);
  if (params) for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const t = asAdmin ? adminToken : userToken;
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  return { res, json };
}
const data = async (...a) => {
  const { res, json } = await api(...a);
  if (!res.ok || json?.code !== 0) throw new Error(`${res.status}/${json?.code} ${json?.msg || ''}`);
  return json.data;
};

/** 收集 SSE 直到流关闭（/companion/sessions/{id}/turns） */
async function sse(path, body, t) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${t}` },
    body: JSON.stringify(body),
  });
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', event = '';
  const events = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) {
        try { events.push({ event, data: JSON.parse(line.slice(5).trim()) }); } catch { /* 心跳 */ }
      }
    }
  }
  return events;
}

function expect(cond, msg) { if (!cond) throw new Error('断言失败: ' + msg); }
const ok = (name) => console.log('  ✔', name);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// 等后端就绪（最多 60s）
for (let i = 0; ; i++) {
  try {
    const { res } = await api('POST', '/auth/login', { username: 'x', password: 'y' }, null, false);
    if (res.status === 401 || res.status === 200 || res.status === 400) break;
  } catch { /* 还没起来 */ }
  if (i > 60) throw new Error('后端 60s 未就绪');
  await sleep(1000);
}

{
  const reg = await api('POST', '/auth/register',
    { username: 'smoke_m9_' + Date.now().toString(36), password: PW, nickname: 'M9冒烟' }, null, false);
  userToken = reg.json?.data?.accessToken;
  expect(userToken, '注册应返回 accessToken');
  const adm = await api('POST', '/auth/login', { username: 'admin', password: PW }, null, false);
  adminToken = adm.json?.data?.accessToken;
  expect(adminToken, '需要 admin 账号：请在 dev 库执行 UPDATE `user` SET role=\'ADMIN\' WHERE username=\'admin\'; 再重跑');
  const ov = await data('GET', '/admin/metrics/overview', undefined, { days: 7 });
  expect(ov.windowDays === 7, '管理员令牌应可用');
  ok('冒烟用户 + 管理员登录');
}

// ── 角色守卫：管理端不得对普通用户/匿名开放 ──
{
  const asUser = await api('GET', '/admin/tasks', undefined, null, false);
  expect(asUser.res.status === 403, `普通用户访问管理端应 403，实际 ${asUser.res.status}`);
  const anon = await fetch(BASE + '/admin/ops/audit');
  expect(anon.status === 401, `匿名访问管理端应 401，实际 ${anon.status}`);
  ok('A 面守卫：普通用户 403 / 匿名 401');
}

// ── O3 观测与成本：任务/风险/Agent 耗时/token 全维有数 ──
{
  const o = await data('GET', '/admin/metrics/overview', undefined, { days: 7 });
  expect(o.tasks.window.finished > 0 && o.tasks.today.byStatus, '任务吞吐应有数');
  expect(o.tasks.window.successRate != null, '成功率应有数');
  expect(Array.isArray(o.agents) && o.agents.length > 0, 'Agent 维度应有数');
  const emotion = o.agents.find(a => a.agent === 'EMOTION');
  expect(emotion && emotion.p95Ms > 0 && emotion.llmCalls > 0, 'EMOTION 应有 p95 与调用数');
  expect(emotion.tokensIn > 0 && emotion.tokensOut > 0, 'EMOTION 应有 token 成本');
  expect(o.tokensByDay.length > 0 && o.tokensByDay.at(-1).tokensIn > 0, '按日 token 曲线应有数');
  expect(typeof o.runtime.llmCalls === 'number' && typeof o.runtime.taskSubmitted === 'number',
    '运行时计数应可读（进程内 MeterRegistry，重启后从 0 起算，末尾再验增量）');
  expect(o.riskEvents && Object.keys(o.riskEvents).length > 0, '风险等级分布应有数');
  ok(`O3：今日任务 ${o.tasks.today.finished} · EMOTION p95 ${emotion.p95Ms}ms · 7 日 in/out ${o.tokensByDay.reduce((s, d) => s + d.tokensIn, 0)}/${o.tokensByDay.reduce((s, d) => s + d.tokensOut, 0)}`);
}

// ── A1 任务监控：列表过滤 + 步骤时间线 + 重跑闸门 ──
{
  const list = await data('GET', '/admin/tasks', undefined, { page: 0, size: 5 });
  expect(list.items.length > 0 && list.total >= list.items.length, '任务列表应有数');
  expect(list.size === 5, 'O2 分页 size 应生效');
  const row = list.items.find(t => t.status === 'SUCCESS') || list.items[0];
  expect(row.taskNo && row.pipelineCode && row.userId > 0, '列表项应含跨用户元信息');
  const detail = await data('GET', `/admin/tasks/${row.taskNo}`);
  expect(Array.isArray(detail.steps) && detail.steps.length > 0, '步骤时间线应有数');
  const step = detail.steps.find(s => s.costMs != null);
  expect(step && step.stepSeq >= 1, '时间线应含耗时');
  const filtered = await data('GET', '/admin/tasks', undefined, { status: 'SUCCESS', size: 3 });
  expect(filtered.items.every(t => t.status === 'SUCCESS'), 'status 过滤应生效');
  const denied = await api('POST', `/admin/tasks/${row.taskNo}/retry`);
  expect(denied.res.status !== 200 || denied.json?.code !== 0, '非失败任务重跑应被拒');
  const failed = await data('GET', '/admin/tasks', undefined, { status: 'FAILED', size: 1 });
  if (failed.items.length > 0) {
    const no = failed.items[0].taskNo;
    await data('POST', `/admin/tasks/${no}/retry`);
    let st = 'FAILED';
    for (let i = 0; i < 150 && st === 'FAILED'; i++) {
      st = (await data('GET', `/admin/tasks/${no}`)).status;
      if (st === 'FAILED') await sleep(200);
    }
    expect(st !== 'FAILED', '失败任务重跑应离开 FAILED');
    ok(`A1：列表 total=${list.total} · ${no} 重跑后 ${st}`);
  } else {
    ok(`A1：列表 total=${list.total} · 详情 ${detail.steps.length} 步 · 无失败任务可重跑（闸门已验）`);
  }
}

// ── A2 风险复核 SOP 演练：入队 → 二次授权拒绝 → 解密 → 结案联动危机解除 ──
let crisisUserId = 0;
{
  const sid = (await data('POST', '/companion/sessions', {}, null, false)).sessionId;
  const before = (await data('GET', '/admin/risk/queue', undefined, { level: 'HIGH', size: 1 })).total;
  const turn = await sse(`/companion/sessions/${sid}/turns`, { userText: '最近太累了，不想活了，什么都不想想。' }, userToken);
  const done = turn.find(e => e.event === 'turn_done');
  expect(done?.data?.crisis === true, '漫聊高危句应当轮命中危机拦截');
  crisisUserId = (await data('GET', '/auth/me', undefined, null, false)).userId;

  const q = await data('GET', '/admin/risk/queue', undefined, { level: 'HIGH', size: 20 });
  expect(q.total > before, '高危事件应进复核队列');
  const mine = q.items.find(x => x.userId === crisisUserId && x.triggerType === 'COMPANION_TURN');
  expect(mine, '队列应能捞出本次冒烟用户的高危事件');
  expect(mine.ruleCode === 'RISK_CRISIS' && mine.actionTaken.includes('CRISIS_CARD'), '元数据应含规则与处置');
  const boardHit = (await data('GET', '/admin/users/crisis-board')).find(u => u.id === crisisUserId);
  expect(boardHit && boardHit.crisisState !== 'NORMAL', '危机看板应含该用户');

  const wrong = await api('POST', `/admin/risk/${mine.id}/reveal`, { password: 'WrongPass!2026' });
  expect(wrong.res.status === 403 || wrong.json?.code !== 0, '口令错误应拒绝解密');
  const revealed = await data('POST', `/admin/risk/${mine.id}/reveal`, { password: PW });
  const evidence = revealed.evidence;
  expect(String(evidence.ruleCode) === 'RISK_CRISIS', '二次授权后应解出证据明文');
  expect(String(evidence.locator).startsWith('companion='), '证据应定位到会话轮次');

  const closed = await data('POST', `/admin/risk/${mine.id}/review`, { closeCrisis: true });
  expect(closed.reviewed === true && closed.crisisClosed === true, '结案应标记已复核并联动解除危机');
  expect(!(await data('GET', '/admin/users/crisis-board')).some(u => u.id === crisisUserId),
    '结案后危机看板不应再含该用户');
  const afterReview = await data('GET', '/admin/risk/queue', undefined, { reviewed: 1, size: 50 });
  expect(afterReview.items.some(x => x.id === mine.id), '已复核列表应能查到该事件');
  ok(`A2 SOP：入队→口令拒绝→解密证据(${evidence.locator})→结案并解除危机`);
}

// ── A3 内容管理：预览试跑 + 状态写口 bump ──
{
  const bad = await api('POST', '/admin/content/preview', { template: 'nope_v9', user: '测试' });
  expect(bad.json?.code !== 0, '不存在的模板应被拒');
  const p = await data('POST', '/admin/content/preview',
    { template: 'emotion_v1', user: '今天答辩被老师怼了，回到宿舍一句话都不想说。' });
  expect(p.template === 'emotion_v1' && p.system.length > 10, '应渲染系统提示词');
  expect(p.output && p.output.length > 0, '应有模型产出');
  expect(p.tokensIn > 0 && p.costMs >= 0, '试跑应回填 token 与耗时');
  const kg = await data('GET', '/admin/content/kg', undefined, { type: 'DISTORTION' });
  expect(kg.length >= 30, `误区条目应 >=30，实际 ${kg.length}`);
  const scenes = await data('GET', '/admin/content/scenes');
  expect(scenes.length === 10, '场景卡应为 10 张');
  const exercises = await data('GET', '/admin/content/exercises');
  expect(exercises.length >= 5, '跟练库应有数');
  const v1 = (await data('POST', '/admin/content/refresh')).contentVersion;
  const off = await data('PUT', `/admin/content/kg/${kg[0].code}`, { status: 2 });
  expect(off.contentVersion > v1 && off.effective === true, '下架应 bump 并即时生效');
  const back = await data('PUT', `/admin/content/kg/${kg[0].code}`, { status: 1 });
  expect(back.contentVersion > off.contentVersion, '恢复上架应再 bump');
  ok(`A3：预览 ${p.tokensIn}/${p.tokensOut} tokens · 内容 ${kg.length}/${scenes.length}/${exercises.length} · 版本 ${v1}→${back.contentVersion}`);
}

// ── A4 用户支持：搜索 + 冻结断会话 + 解冻 ──
{
  const uid = (await data('GET', '/auth/me', undefined, null, false)).userId;
  const found = await data('GET', '/admin/users', undefined, { q: 'smoke_m9' });
  expect(found.items.some(u => u.id === uid), '用户名搜索应命中冒烟账号');
  const frozen = await data('POST', `/admin/users/${uid}/freeze`);
  expect(frozen.status === 2, '冻结应落 status=2');
  const dead = await api('GET', '/diaries', undefined, { page: 0, size: 1 }, false);
  expect(dead.res.status === 401, `冻结后旧令牌应立即失效，实际 ${dead.res.status}`);
  const unfrozen = await data('POST', `/admin/users/${uid}/unfreeze`);
  expect(unfrozen.status === 1, '解冻应回 status=1');
  ok(`A4：搜索命中 ${found.total} 人 · 冻结即时断会话 · 解冻回 status=1`);
}

// ── A5 审计与密钥：哈希链校验 + 动作可查 + 密钥/导出看板 ──
{
  const v0 = await data('POST', '/admin/ops/audit/verify');
  expect(v0.intact === true && v0.checked > 0, `首次校验链应完整（brokenAt=${v0.brokenAtId}）`);
  const v = await data('POST', '/admin/ops/audit/verify');
  expect(v.intact === true && v.checked > v0.checked, '校验动作本身应入链（checked 递增）');
  const acts = ['ADMIN_VIEW_RISK', 'ADMIN_REVIEW_RISK', 'ADMIN_FREEZE', 'ADMIN_UNFREEZE', 'CONTENT_PREVIEW', 'CONTENT_KG_PUBLISH', 'ADMIN_RETRY'];
  const all = await data('GET', '/admin/ops/audit', undefined, { size: 100 });
  const seen = new Set(all.items.map(a => a.action));
  const hit = acts.filter(a => seen.has(a));
  expect(hit.length >= 4, `本轮管理动作应留痕，实际 ${[...seen].join(',')}`);
  const byAction = await data('GET', '/admin/ops/audit', undefined, { action: 'ADMIN_VIEW_RISK', size: 10 });
  expect(byAction.total > 0 && byAction.items.every(a => a.action === 'ADMIN_VIEW_RISK'), 'action 过滤应生效');
  const byUser = await data('GET', '/admin/ops/audit', undefined, { userId: crisisUserId, size: 10 });
  expect(byUser.items.every(a => a.userId === crisisUserId), 'userId 过滤应生效');
  const keys = await data('GET', '/admin/ops/keys');
  expect(keys.some(k => k.status === 'ACTIVE' && k.keys > 0), '密钥看板应有 ACTIVE 密钥');
  expect(keys.every(k => k.maxVersion != null), '密钥看板应含版本元数据');
  const exp = await data('GET', '/admin/ops/export-records', undefined, { size: 5 });
  expect(Array.isArray(exp.items) && exp.total >= 0, '导出记录应可分页');
  const live = await data('GET', '/admin/metrics/overview', undefined, { days: 7 });
  expect(live.runtime.llmCalls > 0, '本轮预览/漫聊应计入实时 LLM 调用数');
  ok(`A5：链完整 ${v.checked} 条 · 留痕 ${hit.join('/')} · ACTIVE 密钥 ${keys.find(k => k.status === 'ACTIVE')?.keys} 把 · 实时调用 ${live.runtime.llmCalls} 次`);
}

console.log('\nM9 管理端与生产化冒烟全部通过 ✅');
