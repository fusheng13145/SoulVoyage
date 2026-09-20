// M10 冒烟（dev 真机 MySQL + Redis）：上线门禁收口 + OpenAI 兼容真协议链路。
// 用法：
//   node deploy/tools/smoke_m10.mjs          # 门禁段（provider=mock 的后端即可）
//   node deploy/tools/smoke_m10.mjs --llm    # 追加真协议段：后端须以
//        SV_LLM_PROVIDER=openai SV_LLM_BASE_URL=http://127.0.0.1:8787/v1 SV_LLM_API_KEY=sk-stub SV_LLM_MODEL=stub-companion-8k
//        起好，并先 `node deploy/tools/llm_stub.mjs`（桩自带 /__stats 与故障注入 /__ctl）
// 中文一律走 fetch（Git Bash 的 curl 会把中文编成 GBK 字节，后端只会回 400/2001）。
const BASE = 'http://localhost:8080/api/v1';
const STUB = 'http://127.0.0.1:8787';
const LLM = process.argv.includes('--llm');
const PW = 'Passw0rd!2026';
const ADMIN = { username: 'admin', password: PW };

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
const sleep = ms => new Promise(r => setTimeout(r, ms));

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

/** 逐块读 SSE：时间戳按到达时刻记，才能量出首字延迟与分块数（真流式的验收口径） */
async function sseTurn(path, body, token, deltaEvent) {
  const t0 = Date.now();
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const events = [];
  let buf = '';
  for await (const chunk of res.body) {
    buf += Buffer.from(chunk).toString('utf8');
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i);
      buf = buf.slice(i + 2);
      const name = /^event:\s*(.+)$/m.exec(block)?.[1]?.trim();
      const raw = /^data:\s*(.+)$/m.exec(block)?.[1];
      if (!name) continue;
      let data = {};
      try {
        data = JSON.parse(raw);
      } catch {
        /* 非 JSON data 原样留着 */
      }
      events.push({ event: name, data, at: Date.now() - t0 });
    }
  }
  const deltas = events.filter(e => e.event === deltaEvent);
  const done = events.find(e => e.event === 'turn_done');
  return {
    httpStatus: res.status,
    events,
    firstDeltaMs: deltas.length ? deltas[0].at : null,
    deltaCount: deltas.length,
    deltaText: deltas.map(e => e.data.text).join(''),
    done,
    totalMs: Date.now() - t0,
  };
}

const stubCtl = async body => (await fetch(STUB + '/__ctl', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})).json();
const stubStats = async () => (await fetch(STUB + '/__stats')).json();

const today = new Date().toISOString().slice(0, 10);
const newUser = async prefix => {
  const username = prefix + '_' + Date.now().toString(36).slice(-6) + Math.floor(Math.random() * 900 + 100);
  return { username, ...(await api('POST', '/auth/register', { username, password: PW, nickname: '冒烟同学' })) };
};

// ============ 1. 真 HTTP 校验面（单测直调 controller 时 @Valid 不生效，这里补上） ============
const u1 = await newUser('m10_smoke');
const tk1 = u1.accessToken;
console.log('\n[1] 入参校验真路径');
{
  const bad1 = await req('POST', '/auth/register', { username: 'a', password: 'short' });
  ok('注册入参非法 → HTTP 400 + 非零码', bad1.status === 400 && bad1.code !== 0, `${bad1.status}/${bad1.code}`);
  const bad2 = await req('POST', '/tasks', { pipelineCode: '', payload: {} }, tk1);
  ok('建任务缺 pipelineCode → HTTP 400', bad2.status === 400 && bad2.code !== 0, `${bad2.status}/${bad2.code}`);
  const bad3 = await req('POST', '/simulations', { sceneCode: '  ', difficulty: 'NORMAL' }, tk1);
  ok('训练开场缺场景码 → HTTP 400（@Valid 真生效）', bad3.status === 400 && bad3.code !== 0,
    `${bad3.status}/${bad3.code}`);
  const sid0 = (await api('POST', '/companion/sessions', {}, tk1)).sessionId;
  const longTurn = await req('POST', `/companion/sessions/${sid0}/turns`, { userText: '很长'.repeat(300) }, tk1);
  ok('SSE 端点入参非法 → 流内 error 而非 500（Accept 只有 text/event-stream）',
    longTurn.status === 200 && longTurn.text.includes('event:error'), `${longTurn.status}/${longTurn.code}`);
  const bad4 = await req('POST', '/reports/999999/feedback', { rating: 'GREAT' }, tk1);
  ok('评价不存在/档位越界 → 非 2xx 且响应是 JSON', bad4.status >= 400 && bad4.code !== 0,
    `${bad4.status}/${bad4.code}`);
}

// ============ 2. 日记流水线 → 报告批注（C2 扩展 + L2 反馈） ============
console.log('\n[2] 日记→报告 与 批注回路');
await api('POST', '/emotions/self-rating', { emotion: '焦虑', valence: -0.5, intensity: 0.7 }, tk1);
const diaryText = '组会汇报被打断三次，回宿舍一路上都在想刚才那句话该怎么接，越想越闷。';
const t2 = Date.now();
const { taskNo } = await api('POST', '/tasks', {
  pipelineCode: 'DIARY_PIPELINE',
  payload: { diaryText, recordDate: today, moodSelfRating: 3 },
  clientReqId: 'm10smk-' + Date.now(),
}, tk1);
let task = await api('GET', `/tasks/${taskNo}`, undefined, tk1);
for (let i = 0; i < 60 && !['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(task.status); i++) {
  await sleep(2000);
  task = await api('GET', `/tasks/${taskNo}`, undefined, tk1);
}
ok('日记任务进入终态', ['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(task.status), task.status + ' ' + (Date.now() - t2) + 'ms');

if (LLM) {
  // 成本真值：步骤日志里的 tokens/model 只可能来自供应商回包（桩上报），不是进程内 Mock 自说自话
  const adminTk = (await api('POST', '/auth/login', ADMIN)).accessToken;
  const detail = await api('GET', `/admin/tasks/${taskNo}`, undefined, adminTk);
  const llmSteps = detail.steps.filter(s => s.llmCalls > 0);
  ok('步骤日志记到 LLM 调用数', llmSteps.length > 0, `${llmSteps.length} 步`);
  ok('步骤日志的 model 是请求侧模型名', llmSteps.every(s => s.model === 'stub-companion-8k'),
    [...new Set(llmSteps.map(s => s.model))].join(','));
  ok('步骤日志 tokensIn/tokensOut 非零（供应商回包口径）',
    llmSteps.every(s => s.tokensIn > 0 && s.tokensOut > 0),
    llmSteps.map(s => `${s.agentCode}:${s.tokensIn}/${s.tokensOut}`).join(' '));
  const ov = await api('GET', '/admin/metrics/overview?days=1', undefined, adminTk);
  const agents = ov.agents.filter(a => a.llmCalls > 0);
  ok('成本看板按 Agent 出数', agents.length > 0, agents.map(a => `${a.agent}=${a.llmCalls}`).join(' '));
} else {
  const reports = await api('GET', '/reports?page=0&size=5', undefined, tk1);
  const rid = reports.items[0]?.id;
  ok('报告已产出', !!rid, rid ? 'report=' + rid : '');
  if (rid) {
    await api('POST', `/reports/${rid}/star`, { starred: true }, tk1);
    const starred = await api('GET', '/reports?starred=true', undefined, tk1);
    ok('收藏后可按 starred 过滤（C2 扩展）', starred.items.some(r => r.id === rid));
    await api('POST', `/reports/${rid}/feedback`, { rating: 'USEFUL', note: '挺准' }, tk1);
    const one = await api('GET', `/reports/${rid}`, undefined, tk1);
    ok('评价落库可读回（L2）', one.feedback === 'USEFUL' && one.feedbackNote === '挺准',
      JSON.stringify(one.feedback) + '/' + JSON.stringify(one.feedbackNote));
    const adminTk = (await api('POST', '/auth/login', ADMIN)).accessToken;
    const ov = await api('GET', '/admin/metrics/overview?days=1', undefined, adminTk);
    ok('反馈档位进看板', JSON.stringify(ov.reportFeedback || {}).includes('USEFUL'),
      JSON.stringify(ov.reportFeedback));
  }
}

// ============ 3. 属主与角色闸门（IDOR 矩阵的线上抽检） ============
console.log('\n[3] 越权闸门抽检');
{
  const u2 = await newUser('m10_intruder');
  const diaries = await api('GET', '/diaries?page=0&size=5', undefined, tk1);
  const mine = diaries.items[0]?.id;
  if (mine) {
    const cross = await req('GET', `/diaries/${mine}`, undefined, u2.accessToken);
    ok('跨账号读日记 → 404（与不存在同形）', cross.status === 404, String(cross.status));
  } else console.log('  · 无日记可检，跳过');
  const ghost = await req('POST', '/notifications/999999/read', {}, tk1);
  ok('读他人/不存在通知 → 404（不再静默 200）', ghost.status === 404, String(ghost.status));
  const exportId = (await api('GET', '/users/me/data-export', undefined, tk1)).fileId;
  const crossExport = await req('POST', `/users/me/data-export/${exportId}`, {}, u2.accessToken);
  ok('跨账号领取导出包 → 403（且不烧掉属主的领取机会）', crossExport.status === 403, String(crossExport.status));
  const first = await req('POST', `/users/me/data-export/${exportId}`, {}, tk1);
  ok('属主首领成功且拿到档案包', first.status === 200 && first.code === 0, String(first.status));
  const second = await req('POST', `/users/me/data-export/${exportId}`, {}, tk1);
  ok('领取即焚：二次领取 → 404', second.status === 404, String(second.status));
  const adminGate = await req('GET', '/admin/tasks?page=0&size=1', undefined, tk1);
  ok('普通用户打管理端 → 403', adminGate.status === 403, String(adminGate.status));
}

// ============ 4. 漫聊回合（真协议段在此量首字与分块） ============
console.log('\n[4] 漫聊回合' + (LLM ? '（OpenAI 兼容真协议）' : '（mock provider）'));
{
  const sid = (await api('POST', '/companion/sessions', {}, tk1)).sessionId;
  const r = await sseTurn(`/companion/sessions/${sid}/turns`,
    { userText: '今天还是有点闷，面试完回来一直在想那句话该怎么接。' }, tk1, 'ai_delta');
  const aiText = r.done?.data?.aiText || '';
  ok('回合流 HTTP 200 且有 turn_done', r.httpStatus === 200 && !!r.done, `${r.events.map(e => e.event).join('>')}`);
  ok('回合文本非空', aiText.length > 0, aiText.slice(0, 24));
  if (LLM) {
    const stats = await stubStats();
    ok('真的走了外部 HTTP（桩计数 +1）', stats.byTemplate?.companion_v1 > 0, JSON.stringify(stats.byTemplate));
    ok('真的走了流式（stream:true 生效）', stats.streamed > 0, 'streamed=' + stats.streamed);
    ok('首字延迟 < 1500ms', r.firstDeltaMs != null && r.firstDeltaMs < 1500, 'first=' + r.firstDeltaMs + 'ms');
    ok('打字机分块 ≥ 3', r.deltaCount >= 3, 'deltas=' + r.deltaCount);
    ok('增量拼起来等于权威全文', r.deltaText === aiText, `${r.deltaText.length}/${aiText.length} 字`);
    ok('整轮 < 6s（手册 §九 训练/回合口径）', r.totalMs < 6000, 'total=' + r.totalMs + 'ms');
    // 危机一票拦截不受 provider 影响
    const crisis = await sseTurn(`/companion/sessions/${sid}/turns`,
      { userText: '不想活了，真的太累了。' }, tk1, 'ai_delta');
    ok('高危句式当轮仍走陪伴话术（不经模型）', !!crisis.done?.data?.crisis, 'crisis=' + crisis.done?.data?.crisis);
  } else {
    const turnId = r.done?.data?.turnId;
    if (turnId) {
      await api('POST', `/companion/turns/${turnId}/no-analyze`, {}, tk1);
      ok('这句别分析可回写', true, 'turn=' + turnId);
    }
    // 唯一一轮被标"别分析"→ 无可消化内容：收段仍 202，但不伪造任务（taskNo 空串）
    const emptyEnd = await req('POST', `/companion/sessions/${sid}/end`, {}, tk1);
    ok('无可消化内容时收段不伪造任务', emptyEnd.status === 202 && emptyEnd.data.taskNo === '',
      `${emptyEnd.status}/taskNo=${JSON.stringify(emptyEnd.data?.taskNo)}`);
    // 有内容时收段真提交 COMPANION_PIPELINE 并跑到终态
    const sid2 = (await api('POST', '/companion/sessions', {}, tk1)).sessionId;
    await sseTurn(`/companion/sessions/${sid2}/turns`,
      { userText: '周末打算把那件拖了很久的事先做一半。' }, tk1, 'ai_delta');
    const digestTask = (await api('POST', `/companion/sessions/${sid2}/end`, {}, tk1)).taskNo;
    ok('收段产出消化任务', !!digestTask, 'taskNo=' + digestTask);
    if (digestTask) {
      let t = await api('GET', `/tasks/${digestTask}`, undefined, tk1);
      for (let i = 0; i < 30 && !['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status); i++) {
        await sleep(2000);
        t = await api('GET', `/tasks/${digestTask}`, undefined, tk1);
      }
      ok('消化任务跑到终态', ['SUCCESS', 'PARTIAL_SUCCESS'].includes(t.status), t.status);
    }
  }
}

// ============ 5. 真协议段：训练回合 + 供应商故障演练 ============
if (LLM) {
  console.log('\n[5] 训练回合真流式');
  const scenes = await api('GET', '/scenes', undefined, tk1);
  const code = scenes[0]?.code;
  const sim = await api('POST', '/simulations', { sceneCode: code, difficulty: 'NORMAL' }, tk1);
  const r = await sseTurn(`/simulations/${sim.simulateId}/turns`,
    { userText: '我想再解释一下上次那件事，不是我故意的。' }, tk1, 'npc_delta');
  const npcText = r.done?.data?.npcText || '';
  ok('训练回合有权威全文', !!r.done && npcText.length > 0, npcText.slice(0, 24));
  ok('训练首字 < 1500ms', r.firstDeltaMs != null && r.firstDeltaMs < 1500, 'first=' + r.firstDeltaMs + 'ms');
  ok('训练整轮 < 6s', r.totalMs < 6000, 'total=' + r.totalMs + 'ms');
  ok('训练增量拼起来等于权威全文', r.deltaText === npcText, `${r.deltaText.length}/${npcText.length} 字`);

  console.log('\n[6] 供应商故障演练（用户侧必须只见降级，不见 500）');
  const sid = (await api('POST', '/companion/sessions', {}, tk1)).sessionId;
  const turn = async text => sseTurn(`/companion/sessions/${sid}/turns`, { userText: text }, tk1, 'ai_delta');
  for (const [mode, what] of [['reject', '上游 429'], ['truncate', '流断在半路（无 [DONE]）'], ['invalid', '回包不合契约']]) {
    await stubCtl({ mode });
    const r = await turn(`换个说法继续聊，测试${mode}。`);
    const txt = r.done?.data?.aiText || '';
    ok(`${mode}（${what}）→ HTTP 200 + turn_done`, r.httpStatus === 200 && !!r.done);
    ok(`${mode} → 降级模板句接手（非空且不半句）`, txt.length > 0 && !txt.includes('{"'), txt.slice(0, 20));
    if (mode === 'truncate')
      ok('truncate → 半句未被当作完整回复', !r.done?.data?.aiText?.endsWith('我在。慢'), txt);
  }
  await stubCtl({ mode: 'ok' });
  const back = await turn('恢复之后我再说一句试试。');
  ok('故障恢复后回到真流式', back.deltaCount >= 3 && !!back.done, 'deltas=' + back.deltaCount);
}

// ============ 6. 注销即遗忘（冷静期 → 撤回，全链路线上口径） ============
console.log('\n[7] 注销链路（可逆段）');
{
  const u = await newUser('m10_gone');
  await api('POST', '/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    payload: { diaryText: '这周答辩被追问到说不出话。', recordDate: today, moodSelfRating: 2 },
    clientReqId: 'm10gone-' + Date.now(),
  }, u.accessToken);
  const wrong = await req('POST', '/users/me/delete', { password: 'WrongPass!1' }, u.accessToken);
  ok('注销需密码重验（错密码不放行）', wrong.status >= 400 && wrong.code !== 0, `${wrong.status}/${wrong.code}`);
  await api('POST', '/users/me/delete', { password: PW }, u.accessToken);
  const afterDelete = await req('GET', '/auth/me', undefined, u.accessToken);
  ok('注销申请即吊销全部令牌 → 401', afterDelete.status === 401, String(afterDelete.status));
  const re = await api('POST', '/auth/login', { username: u.username, password: PW });
  ok('冷静期内仍可登录（撤回入口）', !!re.accessToken);
  await api('POST', '/users/me/delete/cancel', {}, re.accessToken);
  const me = await api('GET', '/auth/me', undefined, re.accessToken);
  ok('撤回后账号恢复正常且数据仍在', me.status !== 3 && !!me.username, 'status=' + me.status);
  const diaries = await api('GET', '/diaries?page=0&size=5', undefined, re.accessToken);
  ok('撤回后日记可读（密钥未销毁）', Array.isArray(diaries.items), diaries.items.length + ' 条');
}

await (LLM ? stubCtl({ mode: 'ok' }) : Promise.resolve());
console.log(`\nM10 冒烟${LLM ? '（含真协议段）' : ''}：通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log('失败清单:\n  - ' + fails.join('\n  - '));
  process.exitCode = 1;
}
