// M16 真机降级验证：Neo4j 不可用时，KG 检索须回落 DbKgService，Agent 链路不得失败。
//
// 验证方式（故障注入）：本脚本需要在「Neo4j 停止」与「Neo4j 运行」两种状态下各跑一次——
//   运行态：基线，图查询正常命中；
//   停止态：ensureSynced() 失败 / Cypher 抛错 → viaGraph 回落 DB，复盘任务仍应 SUCCESS。
// 两次输出应均为通过，差异体现在后端日志（kg graph ... fallback to db impl）。
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
expect(t.status === 'SUCCESS', `图库不可用时复盘任务仍应 SUCCESS（回落 DB），实际 ${t.status}`);

const rp = (await api('GET', '/reports', null, { page: 0, size: 5 })).json.data;
const simRp = rp.items.find(x => x.type === 'SIMULATE');
expect(simRp, '应生成 SIMULATE 复盘报告');
const rpd = (await api('GET', `/reports/${simRp.id}`)).json.data;
const rc = String(rpd.content.referenceCase ?? '');
console.log(`  · [${label}] 报告 referenceCase: ${rc}`);
expect(rc.startsWith('cc_'), '报告应仍含闭集校验过的 referenceCase（回落路径取 DB）');

console.log(`\nM16 降级链路验证通过 ✅ [${label}] —— Neo4j ${label === 'fallback' ? '不可用' : '可用'}时 KG 链路均未失败\n`);
