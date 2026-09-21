// M14 冒烟（dev 真机 MySQL + Redis）：微信小程序端登录链路走真接口——
//   未绑定只回票 / 错口令不烧票 / 一号一微信双向 / 注册复用 Web 校验 / 解绑即时生效 / 匿名口 IP 窗。
// 前置：dev 后端起在 :8080（SV_WX_PROVIDER 缺省即 mock，无需 AppID）；已执行 deploy/sql/migrate_m14.sql。
//   后端不在 8080 时用 SV_ORIGIN 指过去，例如：SV_ORIGIN=http://localhost:8081 node deploy/tools/smoke_m14.mjs
// 中文一律走 fetch（Git Bash 的 curl 会把中文编成 GBK 字节，后端只会回 400/2001）。
// 说明：限流键取 X-Forwarded-For，每段用独立出口 IP，段与段之间不互相牵连（真机没有反向代理时这就是唯一可信来源）。

const BASE = (process.env.SV_ORIGIN || 'http://localhost:8080') + '/api/v1';
const PW = 'Passw0rd!2026';

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

async function req(method, p, body, token, ip) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (ip) headers['X-Forwarded-For'] = ip;
  const res = await fetch(BASE + p, {
    method,
    headers,
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
const api = async (m, p, b, t, ip) => {
  const r = await req(m, p, b, t, ip);
  if (r.code !== 0) throw new Error(`${m} ${p} -> ${r.code} ${r.msg}`);
  return r.data;
};

const seq = (() => {
  let n = 0;
  return p => `${p}${(++n).toString(36)}${Date.now().toString(36).slice(-4)}`;
})();
const wxCode = () => seq('smoke-m14-');
const newUser = async ip => {
  const username = seq('m14s');
  const d = await api('POST', '/auth/register', { username, password: PW, nickname: '小程序冒烟' }, null, ip);
  return { username, token: d.accessToken, userId: d.userId };
};
const login = (code, ip) => req('POST', '/auth/wechat/login', { code }, null, ip);
const ticketOf = async (code, ip) => {
  const r = await login(code, ip);
  if (r.code !== 0) throw new Error(`取票失败 -> ${r.code} ${r.msg}`);
  if (r.data.bound) throw new Error('该微信已绑定，取不到新票');
  return r.data.bindTicket;
};

console.log('M14 小程序端登录 · dev 真机冒烟');

// —— ① 未绑定的微信：只回票，不回身份 ——
{
  const ip = '10.14.1.11';
  const r = await login(wxCode(), ip);
  ok('匿名微信登录可达（无需 AppID/HTTPS）', r.status === 200 && r.code === 0, `${r.status}/${r.code} ${r.msg}`);
  ok('未绑定 → bound=false 且不发令牌', r.data?.bound === false && r.data?.token == null);
  ok('回一张 32 位一次性票 + 有效期', /^[0-9a-f]{32}$/.test(r.data?.bindTicket || ''), `expires=${r.data?.ticketExpiresInSeconds}s`);
  ok('响应零 openid 泄露', !/openid/i.test(r.text) && !r.text.includes('mock-'), r.text.slice(0, 60));
  const blank = await req('POST', '/auth/wechat/login', { code: '  ' }, null, ip);
  ok('空白凭证 → 2001 中文话术', blank.code === 2001, `${blank.code} ${blank.msg}`);
}

// —— ② 错口令不烧票（与"越权不消耗导出链接"同一立场）——
{
  const ip = '10.14.1.12';
  const a = await newUser(ip);
  const t = await ticketOf(wxCode(), ip);
  const wrong = await req('POST', '/auth/wechat/bind', { bindTicket: t, username: a.username, password: 'WrongPwd!2026' }, null, ip);
  ok('错口令 → 1003/401（与 Web 登录同码同状态）', wrong.code === 1003 && wrong.status === 401, `${wrong.status}/${wrong.code} ${wrong.msg}`);
  const right = await req('POST', '/auth/wechat/bind', { bindTicket: t, username: a.username, password: PW }, null, ip);
  ok('同一张票重试即通（失败没烧票）', right.code === 0 && right.data?.userId === a.userId, `userId=${right.data?.userId}`);
  const again = await req('POST', '/auth/wechat/bind', { bindTicket: t, username: a.username, password: PW }, null, ip);
  ok('票用后即焚 → 1006（不是静默成功）', again.code === 1006 && again.status === 400, `${again.status}/${again.code} ${again.msg}`);
  const ghost = await req('POST', '/auth/wechat/bind', { bindTicket: 'f'.repeat(32), username: a.username, password: PW }, null, ip);
  ok('伪造票只能换来 1006，碰不到任何账号', ghost.code === 1006);
}

// —— ③ 绑定后免口令进入 + 资料页只回"绑没绑" ——
{
  const ip = '10.14.1.13';
  const a = await newUser(ip);
  const code = wxCode();
  await req('POST', '/auth/wechat/bind', { bindTicket: await ticketOf(code, ip), username: a.username, password: PW }, null, ip);
  const me = await req('GET', '/auth/me', undefined, a.token, ip);
  ok('/auth/me 暴露 wechatBound=true', me.data?.wechatBound === true);
  ok('/auth/me 不回 openid 值', !/mock-|openid/i.test(me.text));
  const again = await login(code, ip);
  ok('同一微信再登录 → 免口令回令牌', again.data?.bound === true && !!again.data?.token?.accessToken);
  ok('两次落回同一账号', again.data?.token?.userId === a.userId, `${again.data?.token?.userId} vs ${a.userId}`);
  const other = await login(wxCode(), ip);
  ok('换一个微信 → 仍是未绑定分支', other.data?.bound === false);
}

// —— ④ 一号一微信：双向都拒 ——
{
  const ip = '10.14.1.14';
  const a = await newUser(ip);
  const b = await newUser(ip);
  const shared = wxCode();
  const t1 = await ticketOf(shared, ip);
  const t2 = await ticketOf(shared, ip);   // 未绑定时每次登录各发一张票
  await api('POST', '/auth/wechat/bind', { bindTicket: t1, username: a.username, password: PW }, null, ip);
  const second = await req('POST', '/auth/wechat/bind', { bindTicket: t2, username: b.username, password: PW }, null, ip);
  ok('同一微信想再绑别的账号 → 1002', second.code === 1002, `${second.status}/${second.code} ${second.msg}`);
  const t3 = await ticketOf(wxCode(), ip);
  const swap = await req('POST', '/auth/wechat/bind', { bindTicket: t3, username: a.username, password: PW }, null, ip);
  ok('已绑账号想换别的微信 → 1002', swap.code === 1002, `${swap.msg}`);
  const after = await req('GET', '/auth/me', undefined, b.token, ip);
  ok('被拒之后别人家数据没被改', after.data?.wechatBound === false);
  const dbA = await req('GET', '/auth/me', undefined, a.token, ip);
  ok('原绑定仍然生效', dbA.data?.wechatBound === true);
}

// —— ⑤ 注册模式：复用 Web 同一条建号校验，冲突不烧票 ——
{
  const ip = '10.14.1.15';
  const a = await newUser(ip);
  const t = await ticketOf(wxCode(), ip);
  const dup = await req('POST', '/auth/wechat/register', { bindTicket: t, username: a.username, password: PW }, null, ip);
  ok('重名 → 1004（走 Web 注册同一条校验）', dup.code === 1004, `${dup.status}/${dup.code} ${dup.msg}`);
  const weak = await req('POST', '/auth/wechat/register', { bindTicket: t, username: seq('m14s'), password: 'short' }, null, ip);
  ok('弱口令 → 2001（不因换端而松一档）', weak.code === 2001, `${weak.status}/${weak.code} ${weak.msg}`);
  const bad = await req('POST', '/auth/wechat/register', { bindTicket: t, username: '微信昵称', password: PW }, null, ip);
  ok('非法用户名字符 → 2001', bad.code === 2001, bad.msg);
  const good = await req('POST', '/auth/wechat/register', { bindTicket: t, username: seq('m14s'), password: PW, nickname: '小程序用户' }, null, ip);
  ok('换一个用户名即成行（票一直没被烧掉）', good.code === 0 && !!good.data?.accessToken, `${good.code} ${good.msg}`);
  const me = await req('GET', '/auth/me', undefined, good.data?.accessToken, ip);
  ok('注册即绑定，不留中间态', me.data?.wechatBound === true && me.data?.nickname === '小程序用户');
}

// —— ⑥ 解绑属主独享、即时生效 ——
{
  const ip = '10.14.1.16';
  const a = await newUser(ip);
  const b = await newUser(ip);
  const code = wxCode();
  await api('POST', '/auth/wechat/bind', { bindTicket: await ticketOf(code, ip), username: a.username, password: PW }, null, ip);
  const foreign = await req('DELETE', '/auth/wechat/binding', undefined, b.token, ip);
  ok('别人的令牌解不掉我的绑定（未绑定者回 2001）', foreign.code === 2001, `${foreign.status}/${foreign.code} ${foreign.msg}`);
  const still = await login(code, ip);
  ok('越权尝试后绑定仍在', still.data?.bound === true);
  const anon = await req('DELETE', '/auth/wechat/binding', undefined, null, ip);
  ok('匿名解绑 → 401（收回入口必须有登录态）', anon.code === 1001 && anon.status === 401, `${anon.status}`);
  const mine = await req('DELETE', '/auth/wechat/binding', undefined, a.token, ip);
  ok('本人解绑 → 0', mine.code === 0, `${mine.code} ${mine.msg}`);
  const after = await login(code, ip);
  ok('解绑即时生效：同一微信回到未绑定', after.data?.bound === false && !!after.data?.bindTicket);
  const afterMe = await req('GET', '/auth/me', undefined, a.token, ip);
  ok('资料页随之翻回未绑定', afterMe.data?.wechatBound === false);
}

// —— ⑦ 冻结账号不因换入口而放行 ——
{
  const ip = '10.14.1.17';
  const a = await newUser(ip);
  const code = wxCode();
  await api('POST', '/auth/wechat/bind', { bindTicket: await ticketOf(code, ip), username: a.username, password: PW }, null, ip);
  const admin = await api('POST', '/auth/login', { username: 'admin', password: PW }, null, ip);
  const frozen = await req('POST', `/admin/users/${a.userId}/freeze`, undefined, admin.accessToken, ip);
  ok('admin 冻结探针账号（前置条件成立）', frozen.code === 0, `${frozen.code} ${frozen.msg}`);
  const viaWx = await login(code, ip);
  ok('冻结后走微信也进不来 → 1002', viaWx.code === 1002, `${viaWx.status}/${viaWx.code} ${viaWx.msg}`);
  await req('POST', `/admin/users/${a.userId}/unfreeze`, undefined, admin.accessToken, ip);
  const back = await login(code, ip);
  ok('解冻后同一微信可再进入', back.data?.bound === true);
}

// —— ⑧ 冷静期账号：微信入口与 Web 同口径放行（只为撤回注销）——
{
  const ip = '10.14.1.18';
  const a = await newUser(ip);
  const code = wxCode();
  await api('POST', '/auth/wechat/bind', { bindTicket: await ticketOf(code, ip), username: a.username, password: PW }, null, ip);
  await api('POST', '/users/me/delete', { password: PW }, a.token, ip);
  const r = await login(code, ip);
  ok('冷静期内走微信仍能进入（用于撤回注销）', r.data?.bound === true && r.data?.token?.deletionPending === true,
    `deletionPending=${r.data?.token?.deletionPending}`);
  await api('POST', '/users/me/delete/cancel', undefined, r.data?.token?.accessToken, ip);
  ok('撤回后账号回到正常态', (await req('GET', '/auth/me', undefined, r.data?.token?.accessToken, ip)).data?.status === 1);
}

// —— ⑨ 匿名口的自有配额：每 IP 20/分，落在 429 ——
{
  const ip = '10.14.1.19';
  let first429 = 0;
  let after = true;
  for (let i = 1; i <= 26; i++) {
    const r = await login(wxCode(), ip);
    if (r.code === 4002) {
      first429 = i;
      // 之后的请求必须继续被挡（不能时好时坏）
      for (let j = 0; j < 3 && after; j++) {
        const x = await login(wxCode(), ip);
        after = x.code === 4002;
      }
      break;
    }
    if (r.code !== 0) {
      first429 = -i;
      break;
    }
  }
  ok('同一出口 IP 连打会撞到自己的闸门', first429 > 0 && first429 <= 21, `第 ${first429} 次被挡`);
  ok('被挡时统一是 4002/429（不是 500）', first429 > 0 && after, `after=${after}`);
  const otherIp = await login(wxCode(), '10.14.1.20');
  ok('闸门按 IP 分账，别的出口不受牵连', otherIp.code === 0);
}

console.log(`\n结果：${pass} 项通过，${fails.length} 项失败`);
fails.forEach(f => console.log('  ! ' + f));
process.exit(fails.length ? 1 : 0);
