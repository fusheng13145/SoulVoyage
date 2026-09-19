// M5 真机冒烟：S3 认证加固 → S4 SSE 断点重放 → C1 日记本全链路 → S2 导出+注销端到端
// 用法：后端起在 :8080 后 `node deploy/tools/smoke_m5.mjs`
const BASE = 'http://localhost:8080/api/v1';
let token = '';

async function raw(method, path, body, { bearer = true, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(bearer && token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch { /* 非 JSON（如 SSE 断言状态码时） */ }
  return { res, json };
}

async function api(method, path, body, opts) {
  const { res, json } = await raw(method, path, body, opts);
  if (!json || json.code !== 0)
    throw Object.assign(new Error(`${method} ${path} -> HTTP ${res.status} ${json?.code} ${json?.msg}`),
      { status: res.status, code: json?.code });
  return json.data;
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const ok = (name) => console.log('  ✔', name);

/** 消费任务 SSE：记录 `id:` 帧；stopAfter 个事件后主动断线（模拟网络闪断），返回 lastId 供重连 */
async function taskSse(taskNo, { stopAfter = Infinity } = {}) {
  const headers = {
    Accept: 'text/event-stream',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
  const res = await fetch(`${BASE}/tasks/${taskNo}/stream`, { headers });
  if (!res.ok) throw new Error(`SSE 连接失败 ${res.status}`);
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '', ev = '', lastId = null, got = 0, terminal = null;
  const events = [];
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line.startsWith('event:')) ev = line.slice(6).trim();
      else if (line.startsWith('id:')) lastId = line.slice(3).trim();
      else if (line.startsWith('data:')) {
        events.push({ ev, id: lastId, data: JSON.parse(line.slice(5).trim()) });
        if (ev === 'done' || ev === 'error') { terminal = ev; break; }
      }
    }
    if (terminal) break;
    if (++got >= stopAfter && events.length >= stopAfter) {   // 主动掐断，模拟断线
      await reader.cancel();
      return { events, lastId, terminal: null, aborted: true };
    }
  }
  return { events, lastId, terminal, aborted: false };
}

function expect(cond, msg) {
  if (!cond) throw new Error('断言失败: ' + msg);
}

async function waitTask(taskNo, timeoutMs = 120000) {
  const t0 = Date.now();
  for (;;) {
    const v = await api('GET', `/tasks/${taskNo}`);
    if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED'].includes(v.status)) return v;
    if (Date.now() - t0 > timeoutMs) throw new Error('任务超时未完成');
    await sleep(2000);
  }
}

const PW = 'Passw0rd!2026';
const newName = () => 'smoke_m5_' + Date.now().toString(36).slice(-6) + Math.floor(Math.random() * 1e3);

// ---------------- S3 认证加固 ----------------
console.log('== S3 认证加固');
token = (await api('POST', '/auth/register', { username: newName(), password: PW, nickname: 'M5冒烟' })).accessToken;
await api('GET', '/auth/me');
ok('注册+登录，/auth/me 200');

{ // 真实状态码 + 令牌类型隔离：拿 refresh 当 access 用必须 401
  const t = await api('POST', '/auth/login',
    { username: (await api('GET', '/auth/me')).username, password: PW }, { bearer: true });
  const { res } = await raw('GET', '/auth/me', undefined, { bearer: false, headers: { Authorization: `Bearer ${t.refreshToken}` } });
  expect(res.status === 401, 'refresh 冒充 access 应 HTTP 401，实际 ' + res.status);
  ok('refresh 冒充 access → HTTP 401（类型隔离）');
}

{ // refresh 轮换 + 重用检测：旧 refresh 二次使用 → 全端吊销
  const u = newName();
  const t1 = await api('POST', '/auth/register', { username: u, password: PW }, { bearer: false });
  token = t1.accessToken;
  const t2 = await api('POST', '/auth/refresh', { refreshToken: t1.refreshToken }, { bearer: false });
  let reuseErr = null;
  try { await api('POST', '/auth/refresh', { refreshToken: t1.refreshToken }, { bearer: false }); }
  catch (e) { reuseErr = e; }
  expect(reuseErr && reuseErr.status === 401, '重用旧 refresh 应 401，实际 ' + reuseErr?.status);
  const { res } = await raw('GET', '/auth/me', undefined, { bearer: false, headers: { Authorization: `Bearer ${t2.accessToken}` } });
  expect(res.status === 401, '失窃吊销后新 access 也应 401，实际 ' + res.status);
  ok('refresh 轮换 + 重用即全端吊销（epoch）');
}

{ // 登录失败锁定（5 次后连正确密码也拒）
  const u = newName();
  await api('POST', '/auth/register', { username: u, password: PW }, { bearer: false });
  for (let i = 0; i < 5; i++) {
    let e = null;
    try { await api('POST', '/auth/login', { username: u, password: 'WrongPass!9' }, { bearer: false }); }
    catch (err) { e = err; }
    expect(e && e.status === 401, '单次密码错误应 401');
  }
  let locked = null;
  try { await api('POST', '/auth/login', { username: u, password: PW }, { bearer: false }); }
  catch (err) { locked = err; }
  expect(locked && /稍后再试/.test(locked.message), '锁定后正确密码也应被拒且提示稍后再试');
  ok('登录失败 5 次锁定（窗口内拒绝正确密码）');
}

// ---------------- S4 + C1：日记落库、SSE 断点重放、回看/编辑/重分析/草稿 ----------------
console.log('== S4/C1 日记本 + SSE 可靠流');
{
  const t = await api('POST', '/auth/register', { username: newName(), password: PW, nickname: '日记冒烟' }, { bearer: false });
  token = t.accessToken;
}

const diary1 = '今天小组会把我的方案当众改得面目全非，我很生气，晚上一直睡不着。';
{
  const { taskNo } = await api('POST', '/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    payload: { diaryText: diary1, recordDate: new Date().toLocaleDateString('en-CA'), moodSelfRating: 2 },
    clientReqId: 'm5-' + Date.now(),
  });
  const s = await taskSse(taskNo);
  expect(s.terminal === 'done', '第一条日记流应正常收口');
  const ids = s.events.map(e => Number(e.id));
  expect(ids.every((v, i) => i === 0 || v > ids[i - 1]), '事件 id 单调递增');
  ok(`SSE 全程收口（${s.events.length} 事件，id 连续）`);
  await waitTask(taskNo);
}

{ // 断线重放：收到前 2 个事件后掐断，带 Last-Event-ID 重连补到 done
  const { taskNo } = await api('POST', '/tasks', {
    pipelineCode: 'DIARY_PIPELINE',
    payload: { diaryText: '下午导师又临时加了需求，感觉压力很大，今晚先跑一轮数据再说。', recordDate: new Date().toLocaleDateString('en-CA') },
    clientReqId: 'm5r-' + Date.now(),
  });
  const first = await taskSse(taskNo, { stopAfter: 2 });
  expect(first.aborted && first.lastId, '应能中途断线并持有 lastId');
  // 手工重连（带 Last-Event-ID）验证补发
  const rr = await fetch(`${BASE}/tasks/${taskNo}/stream`, {
    headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}`, 'Last-Event-ID': first.lastId },
  });
  expect(rr.ok, 'Last-Event-ID 重连应 200');
  const rReader = rr.body.getReader(); const dec = new TextDecoder();
  let rbuf = '', replayIds = [], rterminal = null;
  for (;;) {
    const { value, done } = await rReader.read();
    if (done) break;
    rbuf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = rbuf.indexOf('\n')) >= 0) {
      const line = rbuf.slice(0, nl).replace(/\r$/, ''); rbuf = rbuf.slice(nl + 1);
      if (line.startsWith('id:')) replayIds.push(Number(line.slice(3).trim()));
      else if (line.startsWith('event:')) { const ev = line.slice(6).trim(); if (ev === 'done' || ev === 'error') rterminal = ev; }
    }
    if (rterminal) { rReader.cancel(); break; }
  }
  expect(replayIds.length > 0 && replayIds.every(v => v > Number(first.lastId)), '重连必须只补发断点之后的事件');
  expect(rterminal === 'done', '补发应直到 done');
  await waitTask(taskNo);
  ok(`S4 断线→Last-Event-ID 补发 ${replayIds.length} 条直到 done`);
}

{ // C1 回看：列表/详情/搜索/编辑 stale/重新分析/草稿
  const list = await api('GET', '/diaries?page=0&size=20');
  expect(list.total === 2, '两篇日记应已落库，实际 ' + list.total);
  const hit = await api('GET', '/diaries?q=' + encodeURIComponent('导师'));
  expect(hit.items.length >= 1, 'q=导师 应命中');
  ok(`日记本列表 ${list.total} 篇 + 服务端解密搜索命中 ${hit.items.length} 篇`);

  const id = list.items[0].id;
  const d0 = await api('GET', `/diaries/${id}`);
  expect(d0.reports.length >= 2 && d0.reports.every(r => r.stale === 0), '首次分析后报告应齐且未过期');
  expect(d0.emotions.length >= 1, '详情应带当日情绪');

  await api('PUT', `/diaries/${id}`, { content: d0.content + ' 后来我想主动找老师聊聊节奏问题。', moodSelfRating: 3 });
  const d1 = await api('GET', `/diaries/${id}`);
  expect(d1.reports.every(r => r.stale === 1), '编辑后报告应全部置 stale');
  ok('编辑 → 报告置 stale');

  const { taskNo: reTask } = await api('POST', `/diaries/${id}/reanalyze`);
  await waitTask(reTask);
  const d2 = await api('GET', `/diaries/${id}`);
  expect(d2.reports.length >= 2 && d2.reports.some(r => r.stale === 0), '重分析后应出现未 stale 的新报告');
  ok('重新分析闭环（新报告 stale=0）');

  await api('PUT', '/diaries/draft', { content: '草稿箱里的半句话……' });
  expect((await api('GET', '/diaries/draft')).content === '草稿箱里的半句话……', '草稿应可回读');
  await api('DELETE', '/diaries/draft');
  expect((await api('GET', '/diaries/draft')).content === '', '清除草稿后应返回空串');
  ok('服务端草稿 存/取/清');

  await api('DELETE', `/diaries/${list.items[1].id}`);
  expect((await api('GET', '/diaries')).total === 1, '删除后列表应少一篇');
  let gone = null;
  try { await api('GET', `/diaries/${list.items[1].id}`); } catch (e) { gone = e; }
  expect(gone && gone.status === 404, '删除后详情应 404');
  ok('删除（软删 + 详情 404）');
}

// ---------------- S2：数据导出 + 注销端到端 ----------------
console.log('== S2 注销即遗忘 + 数据导出');
{
  const { fileId } = await api('GET', '/users/me/data-export');
  const pack = await api('POST', `/users/me/data-export/${fileId}`);
  expect(Array.isArray(pack.diaries) && pack.diaries.length === 1, '导出包应含现存日记');
  expect(Array.isArray(pack.reports) && pack.reports.length >= 2, '导出包应含报告明文');
  let twice = null;
  try { await api('POST', `/users/me/data-export/${fileId}`); } catch (e) { twice = e; }
  expect(twice, '一次性链接二次领取必须失败（领取即焚）');
  ok(`个人数据包：${Object.keys(pack).join(',')}，领取即焚`);
}

{
  const me = await api('GET', '/auth/me');
  let bad = null;
  try { await api('POST', '/users/me/delete', { password: 'WrongPass!9' }); } catch (e) { bad = e; }
  expect(bad, '注销申请密码复核必须拒绝');
  await api('POST', '/users/me/delete', { password: PW });
  const { res } = await raw('GET', '/auth/me');
  expect(res.status === 401, '申请注销后旧令牌应立刻 401，实际 ' + res.status);
  const re = await api('POST', '/auth/login', { username: me.username, password: PW }, { bearer: false });
  expect(re.deletionPending === true, '冷静期登录应带 deletionPending=true');
  token = re.accessToken;
  await api('POST', '/users/me/delete/cancel');
  const me2 = await api('GET', '/auth/me');
  expect(me2.status === 1, '撤回后状态应回 1');
  ok('注销 → 全端下线 → 冷静期登录 → 撤回恢复正常');
}

console.log('\nM5 冒烟全部通过 ✅');
