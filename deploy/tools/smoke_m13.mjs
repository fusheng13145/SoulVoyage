// M13 冒烟（dev 真机 MySQL + Redis，双实例冒充两个节点）：技术债第 2 条「状态跨节点」真机验证。
// 前置：deploy/local.env 基础上加 SV_DISTRIBUTED=true 起两个实例（同一 MySQL、同一 Redis）——
//   set -a; . deploy/local.env; set +a
//   SERVER_PORT=8081 SV_DISTRIBUTED=true SV_LLM_RATE_PER_MIN=10 java -jar backend/target/soulvoyage-backend-*.jar &
//   SERVER_PORT=8082 SV_DISTRIBUTED=true SV_LLM_RATE_PER_MIN=10 java -jar backend/target/soulvoyage-backend-*.jar &
//   LLM/ASR 用 mock 即可——闸门与供应商无关，用 mock 才更好证明"限流不是上游给的"。
//   Redis 连接沿用 SV_REDIS_HOST/PORT/PASS（本脚本自己直连一次 Redis 捞原始字节做明文反证）。
// 口径：判据语义（4001/4002/领取即焚）与单机版逐字一致，这里只证「另一台节点看到的是同一份状态」。
// 中文一律走 fetch（Git Bash 的 curl 会把中文编成 GBK 字节）。

const A = 'http://localhost:8081/api/v1';
const B = 'http://localhost:8082/api/v1';
const PW = 'Passw0rd!2026';
const ADMIN = { username: 'admin', password: PW };
const SECRET_NOTE = '这段明文绝不该出现在 Redis 里';

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

async function req(base, method, p, body, token, raw = false) {
  const headers = { ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  let payload = body;
  if (!(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    payload = body === undefined ? undefined : JSON.stringify(body);
  }
  const res = await fetch(base + p, { method, headers, body: payload });
  const text = await res.text();
  let json = {};
  if (!raw) {
    try {
      json = JSON.parse(text);
    } catch {
      /* 非 JSON 响应留给断言自己判 */
    }
  }
  return { status: res.status, code: json.code, msg: json.msg, data: json.data, text };
}
const api = async (base, m, p, b, t) => {
  const r = await req(base, m, p, b, t);
  if (r.code !== 0) throw new Error(`${m} ${p} -> ${r.code} ${r.msg}`);
  return r.data;
};

async function newUser(i) {
  const username = `m13_${Date.now().toString(36).slice(-5)}_${i}`;
  const d = await api(A, 'POST', '/auth/register', { username, password: PW, nickname: '多节点冒烟' + i });
  const me = await api(A, 'GET', '/auth/me', undefined, d.accessToken);
  return { username, token: d.accessToken, userId: me.userId };
}

/** SSE 读流：按 `event:`/`data:` 组帧，读到 done/error 或超时即收 */
async function sse(base, p, token, { lastEventId, until = 12000 } = {}) {
  const res = await fetch(base + p, {
    headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(lastEventId ? { 'Last-Event-ID': String(lastEventId) } : {}) },
  });
  if (!res.ok) return { status: res.status, events: [], text: await res.text() };
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  const events = [];
  let id = '';
  let name = '';
  let data = '';
  const deadline = Date.now() + until;
  let buf = '';
  while (Date.now() < deadline) {
    const p = Promise.race([
      reader.read(),
      new Promise(r => setTimeout(() => r({ done: 'timeout' }), Math.max(0, deadline - Date.now()))),
    ]);
    const { done, value } = await p;
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line.startsWith('id:')) id = line.slice(3).trim();
      else if (line.startsWith('event:')) name = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
      else if (line === '') {
        if (name) events.push({ id: Number(id), name, data });
        name = '';
        data = '';
      }
    }
    const last = events[events.length - 1];
    if (last && (last.name === 'done' || last.name === 'error')) break;
  }
  try {
    reader.cancel();
  } catch {
    /* 已关流 */
  }
  return { status: res.status, events };
}

// 极简 RESP 客户端：只为把 Redis 里的原始字节捞出来做「查不到明文」的反证
const net = await import('node:net');
function redisCmd(...args) {
  return new Promise((resolve, reject) => {
    const s = net.connect(Number(process.env.SV_REDIS_PORT || 6379), '127.0.0.1');
    const out = [];
    let settled = false;
    s.on('connect', () => {
      if (process.env.SV_REDIS_PASS) s.write(`*1\r\n$4\r\nAUTH\r\n$${process.env.SV_REDIS_PASS.length}\r\n${process.env.SV_REDIS_PASS}\r\n`);
      s.write(`*${args.length}\r\n${args.map(a => `$${Buffer.byteLength(String(a))}\r\n${a}\r\n`).join('')}`);
    });
    s.on('data', b => {
      out.push(b);
      if (!settled) {
        settled = true;
        setTimeout(() => {
          s.destroy();
          resolve(Buffer.concat(out).toString('utf8'));
        }, 120);
      }
    });
    s.on('error', reject);
    setTimeout(() => {
      s.destroy();
      reject(new Error('redis cmd timeout'));
    }, 3000);
  });
}

// —— [0] 两侧都在跑，且闸门确实挂在 Redis 上 ——
console.log('\n[0] 双实例在线 + 闸门位置');
const adminTk = (await api(A, 'POST', '/auth/login', ADMIN)).accessToken;
ok('admin 登录取得管理令牌', !!adminTk);
for (const [label, base] of [['A/8081', A], ['B/8082', B]]) {
  let gate = '';
  try {
    const m = await api(base, 'GET', '/admin/metrics/overview', undefined, adminTk);
    gate = String(m.runtime?.llmGate || '');
  } catch (e) {
    gate = 'ERR ' + e.message;
  }
  ok(`${label} 闸门跑在 Redis 侧`, gate.includes('gate=redis'), gate);
}

// —— [1] 全局令牌桶：桶容量取 SV_LLM_RATE_PER_MIN（本脚本按 10 跑），一次并发打满两节点 ——
console.log('\n[1] LLM 全局桶跨节点');
{
  const users = [await newUser(11), await newUser(12), await newUser(13)];
  const upload = async (base, tk) => {
    const fd = new FormData();
    fd.append('file', new Blob([new Uint8Array(2048).fill(7)], { type: 'audio/wav' }));
    fd.append('durationMs', '800');
    const res = await fetch(base + '/diaries/voice-transcriptions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${tk}` },
      body: fd,
    });
    const j = await res.json().catch(() => ({}));
    return { status: res.status, code: j.code, msg: j.msg };
  };
  // 24 次并发（每用户 8 次，都在自己的 10 次/分窗内）：桶若是分节点的，两节点各 10 枚 → 放行 ~20 次
  const burst = await Promise.all(
    Array.from({ length: 24 }, (_, i) => upload(i % 2 ? A : B, users[Math.floor(i / 8)].token)),
  );
  const granted = burst.filter(r => r.code === 0).length;
  const globalRejects = burst.filter(r => r.code === 4002 && String(r.msg).includes('模型繁忙')).length;
  ok('并发 24 次的放行量贴着"全局一个桶"', granted >= 4 && granted <= 13, `放行 ${granted}，全局拒 ${globalRejects}`);
  ok('被拒的是全新配额的用户也算在内（桶不认节点也不认人）', globalRejects >= 8, `4002/模型繁忙 ${globalRejects} 次`);
  ok('没有一次被用户级窗误伤（每用户 8 次 < 10 次上限）',
    !burst.some(r => String(r.msg).includes('转写得太快')));
}

// —— [2] 用户级滑动窗：两节点交替调用，写进同一个 Redis 窗 ——
console.log('\n[2] 用户级滑动窗跨节点');
{
  const u = await newUser(21);
  await new Promise(r => setTimeout(r, 65_000));   // 等上一节抽干的桶补满，免得两回事混在一起
  const call = async base => {
    const f = new FormData();
    f.append('file', new Blob([new Uint8Array(1024).fill(9)], { type: 'audio/wav' }));
    f.append('durationMs', '600');
    const res = await fetch(base + '/diaries/voice-transcriptions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${u.token}` },
      body: f,
    });
    return (await res.json().catch(() => ({}))).code;
  };
  let s = 0;
  for (let i = 0; i < 8; i++) if ((await call(i % 2 ? A : B)) === 0) s++;
  const card = await redisCmd('ZCARD', `rl:voice:${u.userId}`);
  const shared = Number(String(card).replace(/[^\d]/g, ''));
  ok('交替两节点的 8 次调用落在同一个窗里', s >= 4 && shared === s, `成功 ${s} 次，Redis 窗计数 ${shared}`);
  ok('窗的键按用户命名（rl:voice:{{userId}}）', shared > 0, `rl:voice:${u.userId} -> ${shared}`);
}

// —— [3] 任务事件流：A 产出、B 订阅与断点续传 ——
console.log('\n[3] SSE 事件跨节点（Stream 缓冲 + pub/sub 中继）');
{
  const u = await newUser(31);
  const diaryText = '小组作业又全是我做的，我说不出话来。' + SECRET_NOTE;
  const t = await api(A, 'POST', '/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    clientReqId: 'm13-' + Date.now(),
    payload: { diaryText, recordDate: new Date().toISOString().slice(0, 10) },
  }, u.token);
  const onB = await sse(B, `/tasks/${t.taskNo}/stream`, u.token);
  const names = onB.events.map(e => e.name);
  const ids = onB.events.map(e => e.id);
  ok('订阅打在另一节点也收到了这一单的全部事件', names.length > 0, `事件 ${names.length} 条：${names.join('/')}`);
  ok('事件 id 单调递增（跨节点仍严格有序）', ids.every((v, i) => i === 0 || v > ids[i - 1]), ids.join(','));
  ok('终态事件在 B 侧出现', names.includes('done') || names.includes('error'), names.at(-1));
  if (names.includes('error')) {
    // 本脚本刻意把桶压到 10/分，流水线自己的推理调用会被自家闸门挡下——这是闸门在工作，不是链路故障
    const view = await api(B, 'GET', `/tasks/${t.taskNo}`, undefined, u.token);
    ok('失败原因落库并跨节点读回：模型繁忙（全局桶压小所致）',
      /模型繁忙|4002/.test(String(view.errorMsg || '')), String(view.errorMsg).slice(0, 60));
  }
  const mid = ids[Math.max(0, Math.floor(ids.length / 2) - 1)];
  const rejoin = await sse(B, `/tasks/${t.taskNo}/stream`, u.token, { lastEventId: mid });
  ok('携带 Last-Event-ID 在另一节点从断点续传', rejoin.events.every(e => e.id > mid) && rejoin.events.length > 0,
    `从 id>${mid} 补发 ${rejoin.events.length} 条`);
  const anon = await req(B, 'GET', `/tasks/${t.taskNo}/stream`, undefined, undefined);
  ok('匿名订阅别人的任务被拒', anon.status === 401, String(anon.status));
}

// —— [4] 导出快照：A 生成、B 领取即焚，Redis 里查不到明文 ——
console.log('\n[4] 导出快照跨节点 + 只落密文');
{
  const u = await newUser(41);
  const t = await api(A, 'POST', '/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    clientReqId: 'm13x-' + Date.now(),
    payload: { diaryText: SECRET_NOTE, recordDate: new Date().toISOString().slice(0, 10) },
  }, u.token);
  for (let i = 0; i < 60; i++) {
    const st = (await api(B, 'GET', `/tasks/${t.taskNo}`, undefined, u.token)).status;
    if (st === 'SUCCESS' || st === 'FAILED' || st === 'PARTIAL_SUCCESS') break;
    await new Promise(r => setTimeout(r, 500));
  }
  const fileId = (await api(A, 'GET', '/users/me/data-export', undefined, u.token)).fileId;
  const raw = await redisCmd('GET', `export:snapshot:${fileId}`);
  ok('快照确实落在共享 Redis 上', raw.startsWith('$') && !raw.startsWith('$-1'), raw.slice(0, 12).trim());
  ok('Redis 字节里查不到导出明文', !raw.includes(SECRET_NOTE) && !raw.includes('"content"'),
    `原始回复 ${raw.length}B`);
  const stranger = await newUser(42);
  const foreign = await req(B, 'POST', `/users/me/data-export/${fileId}`, undefined, stranger.token);
  ok('别人抢先领取 → 1002 无权访问且不消耗链接', foreign.code === 1002, `${foreign.code} ${foreign.msg}`);
  const stillThere = await redisCmd('GET', `export:snapshot:${fileId}`);
  ok('越权尝试后快照仍在（等属主来领）', stillThere.startsWith('$') && !stillThere.startsWith('$-1'));
  const mine = await api(B, 'POST', `/users/me/data-export/${fileId}`, undefined, u.token);
  ok('属主在另一节点领回全量数据', Array.isArray(mine.diaries) && !!mine.generatedAt,
    `diaries ${mine.diaries?.length} 条`);
  ok('领回的明文里确有那句话（说明 Redis 侧确实是加密通道）',
    JSON.stringify(mine).includes(SECRET_NOTE));
  const gone = await req(B, 'POST', `/users/me/data-export/${fileId}`, undefined, u.token);
  ok('领取即焚跨节点成立（二次领取 2002）', gone.code === 2002, `${gone.code} ${gone.msg}`);
}

// —— [5] 进程内闸门（distributed=false）口径不变：由 H2 回归守，这里只核对 Redis 版阈值可配 ——
console.log('\n[5] 现值可观测');
{
  const m = await api(B, 'GET', '/admin/metrics/overview', undefined, adminTk);
  ok('闸门现值把阈值一起报出来', /coins\/min=\d+.*inFlight<=\d+/.test(String(m.runtime.llmGate)), m.runtime.llmGate);
}

console.log(`\n结果：${pass} 通过 / ${fails.length} 失败`);
if (fails.length) {
  console.log(fails.map(f => '  ! ' + f).join('\n'));
  process.exit(1);
}
