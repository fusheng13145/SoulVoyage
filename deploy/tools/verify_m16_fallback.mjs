// M16 真机降级验证：Neo4j 不可用时，KG 检索须回落 DbKgService，Agent 链路不得失败。
//
// 验证方式（故障注入）：本脚本需要在「Neo4j 停止」与「Neo4j 运行」两种状态下各跑一次——
//   运行态：基线，图查询正常命中；
//   停止态：ensureSynced() 失败 / Cypher 抛错 → viaGraph 回落 DB，链路不得整体失败。
// 两次输出应均为通过，差异体现在后端日志（kg graph ... fallback to db impl）。
//
// ⚠ LLM 口径决定通过线：本脚本拦的是"任务整体 FAILED"（KG 降级不得让链路挂掉）。
//   若想复现 M16 当时的 SUCCESS 口径，须以 SV_LLM_PROVIDER=mock 启动后端（进程内回放，输出固定合法）；
//   以 openai 桩运行时，复盘步骤会因 referenceCase 未过 Schema 校验而按设计标为 DEGRADED
//   （任务 PARTIAL_SUCCESS）——那是模型输出侧结果，与 KG 走图还是回落 DB 无关。
//   2026-09-29 三段对照实证：图可用+openai桩 与 图不可用+openai桩 得到完全相同的 DEGRADED 步骤；
//   图不可用+mock 得到 SUCCESS（referenceCase=cc_apology_repair）。
//
// 前置：dev 后端(:8080)已启动，且 SV_NEO4J_ENABLED=true。
// 用法：node deploy/tools/verify_m16_fallback.mjs
const BASE = 'http://127.0.0.1:8080/api/v1';
const PW = 'Passw0rd!2026';
let token = '';

const label = process.argv[2] || 'baseline';

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

function expect(c, m) { if (!c) throw new Error('断言失败: ' + m); }

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
  { username: 'm16fb_' + Date.now().toString(36), password: PW, nickname: 'M16降级' })).json.data.accessToken;
expect(token, '注册应返回 accessToken');
console.log(`  ✔ [${label}] 注册登录`);

// 走 SIMULATE 复盘：其 SIMULATE 中间结果与报告正文都依赖 KgSearchService 的 casesFor 召回
const opened = (await api('POST', '/simulations', { sceneCode: 'DORM_CONFLICT', difficulty: 'NORMAL' })).json.data;
expect(opened.simulateId, '应能开场');

const ev1 = await sse(`/simulations/${opened.simulateId}/turns`, { userText: '我想跟你商量熄灯后的安排' });
expect(ev1.some(e => e.event === 'turn_done'), '首轮 SSE 应正常收口');
console.log(`  ✔ [${label}] 开场 + 首轮对话`);

const finRes = await api('POST', `/simulations/${opened.simulateId}/finish`);
const fin = finRes.json?.data;
expect(fin && fin.taskNo, 'finish 应返回 taskNo: ' + JSON.stringify(finRes.json));

let t = null;
for (let i = 0; i < 150; i++) {
  t = (await api('GET', `/tasks/${fin.taskNo}`)).json.data;
  if (['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(t.status)) break;
  await new Promise(r => setTimeout(r, 200));
}
console.log(`  · [${label}] SIMULATE 复盘任务终态: ${t.status}  (task ${fin.taskNo})`);
// 判据是"链路未整体失败"，而非"必然 SUCCESS"。复盘正文的 referenceCase 由模型产出，其 Schema
// 校验失败会按既定设计标为步骤 DEGRADED、任务 PARTIAL_SUCCESS（4003 语义：不整单报废）。该结果
// 只取决于 LLM 口径，与 KG 走图还是回落 DB 无关（见文件头三段对照实证）。KG 侧的降级证据看
// 后端日志里的 "fallback to db impl" 记录，不靠本断言。
expect(t.status !== 'FAILED', `图库不可用时链路不得整体失败，实际 ${t.status}`);
if (t.status === 'PARTIAL_SUCCESS') {
  console.log('    · 说明：PARTIAL_SUCCESS 来自模型输出校验（步骤 DEGRADED），非 KG 降级所致；'
    + '欲复现 M16 的 SUCCESS 口径请以 SV_LLM_PROVIDER=mock 运行');
}

const rp = (await api('GET', '/reports', null, { page: 0, size: 5 })).json.data;
const simRp = rp.items.find(x => x.type === 'SIMULATE');
expect(simRp, '应生成 SIMULATE 复盘报告');
const rpd = (await api('GET', `/reports/${simRp.id}`)).json.data;
const rc = String(rpd.content.referenceCase ?? '');
console.log(`  · [${label}] 报告 referenceCase: ${rc || '(空)'}`);
// 与任务终态同理：referenceCase 是模型输出侧字段，非 KG 产物。只要求"若给出则须通过闭集校验"；
// 为空时归因于本轮 LLM 口径，不据此判定 KG 降级异常。
expect(rc === '' || rc.startsWith('cc_'),
  `referenceCase 若存在则须是通过闭集校验的引用（回落路径同样取 DB 候选），实际 ${rc}`);

console.log(`\nM16 降级链路验证通过 ✅ [${label}] —— Neo4j ${label === 'fallback' ? '不可用' : '可用'}时 KG 链路均未失败\n`);
