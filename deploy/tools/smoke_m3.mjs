// M3 UC2 真机冒烟：注册 → 场景 → 开场 → 两轮 SSE 对话 → 危机兜底 → 复盘 → 报告
// 用法：node deploy/tools/smoke_m3.mjs
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
  const json = await res.json();
  if (json.code !== 0) throw new Error(`${method} ${path} -> ${json.code} ${json.msg}`);
  return json.data;
}

/** POST-SSE 消费：与前端 postSse 同协议 */
async function sseTurn(simId, userText) {
  const res = await fetch(`${BASE}/simulations/${simId}/turns`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ userText }),
  });
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', cur = '', npcText = '', done = null, crisis = null;
  for (;;) {
    const { value, done: fin } = await reader.read();
    if (fin) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line.startsWith('event:')) cur = line.slice(6).trim();
      else if (line.startsWith('data:')) {
        const data = JSON.parse(line.slice(5).trim());
        if (cur === 'npc_delta') npcText += data.text;
        else if (cur === 'turn_done') done = data;
        else if (cur === 'crisis') crisis = data;
        else if (cur === 'error') throw new Error(`turn error ${data.code} ${data.msg}`);
      }
    }
  }
  return { npcText, done, crisis };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const username = 'smoke_m3_' + Date.now().toString(36).slice(-6);
console.log('== register', username);
token = (await api('POST', '/auth/register',
  { username, password: 'Passw0rd!2026', nickname: '冒烟测试' })).accessToken;

const sceneList = await api('GET', '/scenes');
console.log('== scenes:', sceneList.map((s) => `${s.code}/${s.difficulties.join('|')}`).join('  '));

const opened = await api('POST', '/simulations', { sceneCode: 'DORM_CONFLICT', difficulty: 'HARD' });
console.log(`== opened sim=${opened.simulateId} npc=${opened.npcName} maxTurns=${opened.maxTurns}`);
console.log(`   开场白: ${opened.openingLine}`);

const t1 = await sseTurn(opened.simulateId,
  '我注意到这周有三次很晚，我感到白天没精神，我们可以约定一个折中时间吗');
console.log(`== turn1 mood=${t1.done?.npcEmotion} tension=${t1.done?.tension} tag=${t1.done?.stateTag}`);
console.log(`   NPC: ${t1.npcText}`);

const t2 = await sseTurn(opened.simulateId,
  '你总是这样！你从来不听，自私，闭嘴，烦不烦，我受够你了');
console.log(`== turn2 mood=${t2.done?.npcEmotion} tension=${t2.done?.tension} tag=${t2.done?.stateTag}`);
console.log(`   NPC: ${t2.npcText.slice(0, 60)}…`);

const t3 = await sseTurn(opened.simulateId, '算了，我真的不想活了，没什么意思');
console.log(`== turn3 crisis=${!!t3.crisis} level=${t3.crisis?.level} hotline=${t3.crisis?.hotline}`);
console.log(`   退出话术: ${t3.npcText.slice(0, 46)}…`);

const { taskNo } = await api('POST', `/simulations/${opened.simulateId}/finish`, {});
console.log('== finish -> task', taskNo);
let task;
for (let i = 0; i < 40; i++) {
  task = await api('GET', `/tasks/${taskNo}`);
  if (['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(task.status)) break;
  await sleep(500);
}
console.log(`== review task ${task.status}, avgScore=${task.payload?.overall?.avgScore}`,
  `grades=${(task.payload?.turnScores ?? []).map((s) => s.grade).join('')}`);

const reportId = Number(task.payload.reportId.replace('rp_', ''));
const report = await api('GET', `/reports/${reportId}`);
console.log(`== report #${reportId} type=${report.type} risk=${report.riskLevel} title=${report.title}`);

const replay = await api('GET', `/simulations/${opened.simulateId}`);
console.log(`== transcript status=${replay.status} turns=${replay.turns.length}`,
  `第一轮回显: ${replay.turns[0].userText.slice(0, 14)}…`);

if (task.status !== 'SUCCESS' || report.type !== 'SIMULATE' || report.riskLevel !== 'HIGH') {
  throw new Error('SMOKE FAILED');
}
console.log('SMOKE_OK sim=' + opened.simulateId + ' task=' + taskNo + ' report=' + reportId);
