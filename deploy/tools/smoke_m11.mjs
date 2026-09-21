// M11 冒烟（dev 真机 MySQL + Redis）：语音日记——转写网关、输入源标记、护栏复用与真协议链路。
// 用法：
//   node deploy/tools/smoke_m11.mjs          # 门禁段（SV_ASR_PROVIDER=mock 的后端即可）
//   node deploy/tools/smoke_m11.mjs --asr    # 追加真协议段：后端须以
//        SV_ASR_PROVIDER=openai SV_ASR_BASE_URL=http://127.0.0.1:8788/v1 SV_ASR_API_KEY=sk-stub SV_ASR_MODEL=stub-asr-1
//        起好，并先 `node deploy/tools/asr_stub.mjs`（桩自带 /__stats 与 /__ctl 故障注入）
// 中文一律走 fetch（Git Bash 的 curl 会把中文编成 GBK 字节，后端只会回 400/2001）。
import { mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = 'http://localhost:8080/api/v1';
const ACT = 'http://localhost:8080/actuator';
const STUB = 'http://127.0.0.1:8788';
const ASR = process.argv.includes('--asr');
const PW = 'Passw0rd!2026';
const MAX_BYTES = 5 * 1024 * 1024;
const CRISIS_TEXT = '感觉一切都完了，真的撑不下去，活着没意思。';
const SAMPLE =
  '今天下午去图书馆写了一会儿作业，晚上和室友约了饭，整体还算平静，就是躺下以后有点睡不着。';

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
const today = new Date().toLocaleDateString('en-CA');

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

/** 上传一段"录音"：字节全 0 也够——桩与 Mock 都只按体积/类型应答，不解析声波 */
async function upload(token, { bytes = 64 * 1024, type = 'audio/webm', durationMs } = {}) {
  const form = new FormData();
  const blob = new Blob([new Uint8Array(bytes)], { type });
  form.append('file', blob, 'voice');
  if (durationMs !== undefined) form.append('durationMs', String(durationMs));
  const res = await fetch(BASE + '/diaries/voice-transcriptions', {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  const text = await res.text();
  let json = {};
  try {
    json = JSON.parse(text);
  } catch {
    /* 同上 */
  }
  return { status: res.status, code: json.code, msg: json.msg, data: json.data, text };
}

async function newUser(prefix) {
  const username = prefix + '_' + Date.now().toString(36).slice(-6);
  const d = await api('POST', '/auth/register', { username, password: PW, nickname: '语音冒烟' });
  return { ...d, username };
}

async function awaitTask(taskNo, token) {
  for (let i = 0; i < 90; i++) {
    const t = await api('GET', `/tasks/${taskNo}`, undefined, token);
    if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'].includes(t.status)) return t;
    await sleep(1500);
  }
  throw new Error('任务未收口: ' + taskNo);
}

/** 临时目录里"疑似上传转存"的痕迹（音频一旦落盘必在这里露脸） */
const scratch = () => {
  const dir = tmpdir();
  return new Set(
    readdirSync(dir)
      .filter(n => /^(tomcat|multipart|upload)/i.test(n) || /\.tmp$/i.test(n))
      .map(n => n.replace(/\d{6,}/g, '#')), // 端口/时间戳等易变数字归一，避免假阳性
  );
};

// ============ 1. 参数与鉴权矩阵（转写端点不是"谁能 POST 都行"的开放口） ============
console.log('\n[1] 上传校验与鉴权');
{
  const u = await newUser('m11_v');
  const good = await upload(u.accessToken, { bytes: 64 * 1024, durationMs: 12_345 });
  ok('合法录音转写成功', good.status === 200 && good.code === 0, `${good.status}/${good.code}`);
  ok('转写文本原样交回属主（不加工、不入库）', good.data?.text === SAMPLE || ASR, 'len=' + (good.data?.text || '').length);
  ok('时长随响应回带', good.data?.durationMs === 12_345, String(good.data?.durationMs));

  const anon = await upload(undefined, {});
  ok('未登录不可转写（401/1001）', anon.status === 401 && anon.code === 1001, `${anon.status}/${anon.code}`);
  const badUploads = [
    ['空录音被拒', { bytes: 0 }, 2001],
    ['非音频类型被拒', { type: 'application/x-msdownload' }, 2001],
    ['text/plain 被拒', { type: 'text/plain' }, 2001],
    ['超大被拒', { bytes: MAX_BYTES + 1024 }, 2001],
    ['超时长按 90 秒被拒', { durationMs: 91_000 }, 2001],
  ];
  for (const [label, opt, want] of badUploads) {
    const r = await upload(u.accessToken, opt);
    ok(label, r.code === want, `${r.status}/${r.code} ${r.msg || ''}`);
  }
  const withCodec = await upload(u.accessToken, { type: 'audio/wav; codecs=1' });
  ok('带参数的 MIME 主类型相同即放行', withCodec.code === 0, `${withCodec.status}/${withCodec.code}`);
}

// ============ 2. 语音 → 日记端到端（输入源只是标记，链路一字未改） ============
console.log('\n[2] 语音成稿端到端');
{
  const u = await newUser('m11_e');
  const t = await upload(u.accessToken, { durationMs: 20_000 });
  const text = t.data.text;
  const { taskNo } = await api(
    'POST',
    '/tasks',
    {
      pipelineCode: 'DIARY_PIPELINE',
      payload: { diaryText: text, recordDate: today, moodSelfRating: 3, diarySource: 'VOICE', voiceDurationMs: 20_000 },
      clientReqId: 'm11e-' + Date.now(),
    },
    u.accessToken,
  );
  const done = await awaitTask(taskNo, u.accessToken);
  ok('语音文本走同一条 DIARY_PIPELINE 到终态', done.status === 'SUCCESS', done.status);

  const list = await api('GET', '/diaries?page=0&size=5', undefined, u.accessToken);
  ok('列表带出输入源标记', list.items[0]?.source === 'VOICE', list.items[0]?.source);
  const detail = await api('GET', `/diaries/${list.items[0].id}`, undefined, u.accessToken);
  ok('详情回显语音来源与时长', detail.source === 'VOICE' && detail.voiceDurationMs === 20_000);
  ok('原文与转写逐字一致（加密回读无损）', detail.content === text);
  ok('详情不含任何音频字段（本就没有可回带的东西）', !JSON.stringify(detail).includes('audio'));

  const reports = detail.reports || [];
  ok('四步产物照常生成（TRACE/SUPPORT 两份报告在）', reports.length >= 2, reports.map(r => r.type).join('+'));
  const re = await api('POST', `/diaries/${list.items[0].id}/reanalyze`, undefined, u.accessToken);
  const reDone = await awaitTask(re.taskNo, u.accessToken);
  ok('语音日记可重新分析（复用同一链路）', reDone.status === 'SUCCESS', reDone.status);
}

// ============ 3. 语音不绕过危机一票拦截 ============
console.log('\n[3] 语音路径危机拦截');
{
  const u = await newUser('m11_c');
  const { taskNo } = await api(
    'POST',
    '/tasks',
    {
      pipelineCode: 'DIARY_PIPELINE',
      payload: { diaryText: CRISIS_TEXT, recordDate: today, diarySource: 'VOICE', voiceDurationMs: 9_000 },
      clientReqId: 'm11c-' + Date.now(),
    },
    u.accessToken,
  );
  const done = await awaitTask(taskNo, u.accessToken);
  const me = await api('GET', '/auth/me', undefined, u.accessToken);
  ok('语音标记的危机文本当场进危机态', me.crisisState === 'CRISIS', `task=${done.status} crisis=${me.crisisState}`);
  const d = await api('GET', '/diaries?page=0&size=1', undefined, u.accessToken);
  ok('危机不剥夺数据：语音日记仍成稿可读', d.items[0]?.source === 'VOICE');
}

// ============ 4. 用户级滑动窗（10 次/分） ============
console.log('\n[4] 转写限流');
{
  const a = await newUser('m11_q1');
  const b = await newUser('m11_q2');
  let allowed = 0;
  for (let i = 0; i < 11; i++) {
    const r = await upload(a.accessToken, { bytes: 2048 });
    if (r.code === 0) allowed++;
  }
  ok('同用户每分钟 10 次为顶', allowed === 10, '放行 ' + allowed + ' 次');
  const other = await upload(b.accessToken, { bytes: 2048 });
  ok('别的账号不被牵连', other.code === 0, `${other.status}/${other.code}`);
}

// ============ 5. 音频零留存（声纹不落盘） ============
console.log('\n[5] 音频不留存');
{
  const u = await newUser('m11_p');
  mkdtempSync(join(tmpdir(), 'm11probe')); // 让临时目录本身处于"刚被使用"的状态
  const before = scratch();
  const r = await upload(u.accessToken, { bytes: 4 * 1024 * 1024, durationMs: 80_000 });
  const after = scratch();
  const added = [...after].filter(n => !before.has(n));
  ok('4MB 录音被接受（业务上限 5MB 之内）', r.code === 0, `${r.status}/${r.code}`);
  ok('转写前后临时目录零新增（音频全程只在内存）', added.length === 0, added.join(','));
  const body = JSON.stringify(r.data);
  ok('响应不含任何文件句柄/路径（没有"稍后来取"的口子）', !/\/|\\|fileId|url/i.test(body.replace(/audio\/[a-z0-9]+/gi, '')));
}

// ============ 6. 真协议段（SV_ASR_PROVIDER=openai + asr_stub） ============
if (ASR) {
  console.log('\n[6] OpenAI 兼容真协议转写');
  const before = await (await fetch(STUB + '/__stats')).json();
  const u = await newUser('m11_llm');
  const r = await upload(u.accessToken, { bytes: 99_000, type: 'audio/mp4', durationMs: 23_000 });
  ok('真 HTTP multipart 上行 + JSON 下行', r.code === 0, `${r.status}/${r.code} ${r.msg || ''}`);
  ok('文本只可能来自桩的应答（含字节数与模型名）', /桩转写 99000 字节·stub-asr-1·约 \d+ 秒/.test(r.data?.text || ''), (r.data?.text || '').slice(-30));
  const after = await (await fetch(STUB + '/__stats')).json();
  ok('上行只带标准字段（没在公共端点上夹带私有协议）', after.lastFields === 'model', after.lastFields || '（无）');
  ok('桩侧计数 +1（跨了一次线，不是进程内回放）', after.calls === before.calls + 1, `${before.calls}→${after.calls}`);
  ok('桩侧收到真实音频字节', after.bytes - before.bytes === 99_000, String(after.bytes - before.bytes));
  ok('桩侧看到的仍是音频类型', after.lastType === 'audio/mp4', after.lastType);

  const m = await (await fetch(ACT + '/metrics/sv.asr.call', { headers: { Authorization: `Bearer ${(await api('POST', '/auth/login', { username: 'admin', password: PW })).accessToken}` } })).json().catch(() => null);
  ok('转写用量进了指标（sv.asr.call 有值）', (m?.measurements?.[0]?.value ?? 0) > 0, JSON.stringify(m?.measurements || m?.error || '').slice(0, 60));

  for (const [mode, label, want] of [
    ['reject', '上游 5xx → 4004（不是 500）', 4004],
    ['empty', '空转写 → 4004 且话术可读', 4004],
  ]) {
    await fetch(STUB + '/__ctl', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode }) });
    const bad = await upload(u.accessToken, { bytes: 4096 });
    ok(label, bad.code === want, `${bad.status}/${bad.code} ${bad.msg || ''}`);
  }
  await fetch(STUB + '/__ctl', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'ok' }) });
  const back = await upload(u.accessToken, { bytes: 4096 });
  ok('恢复后回到正常转写', back.code === 0, `${back.status}/${back.code}`);

  const { taskNo } = await api(
    'POST',
    '/tasks',
    { pipelineCode: 'DIARY_PIPELINE', payload: { diaryText: back.data.text, recordDate: today, diarySource: 'VOICE', voiceDurationMs: 5_000 }, clientReqId: 'm11r-' + Date.now() },
    u.accessToken,
  );
  const done = await awaitTask(taskNo, u.accessToken);
  ok('真协议文本同样跑完整条流水线', done.status === 'SUCCESS', done.status);
}

console.log(`\nM11 冒烟${ASR ? '（含真协议段）' : ''}：通过 ${pass} 项，失败 ${fails.length} 项`);
if (fails.length) {
  console.log('失败清单:\n  - ' + fails.join('\n  - '));
  process.exitCode = 1;
}
