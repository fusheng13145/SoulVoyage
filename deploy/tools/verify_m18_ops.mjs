// M18 · G8（图运行时可观测）+ G9（DB↔图对账）的真机验收。
//
// 这两个能力都是"管理端看得见"的性质：G8 让运维一句话读出 KG 当前跑在图侧还是回落 DB、
// 熔断是否打开、同步到哪一版；G9 让运维一句话问出图里缺没缺、多没多。所以验收方式也是
// 读管理端端点、按口径断言，而不是看日志。
//
// 用法：node deploy/tools/verify_m18_ops.mjs [--base http://localhost:8080] [--expect-kg db|neo4j|circuit-open]
//   --expect-kg db           图未启用态：runtime.kg 应为 kg=db(kg_node)，对账应如实报不可用
//   --expect-kg neo4j        图可用态：runtime.kg 应 kg=neo4j 且已同步，对账应 consistent=true
//   --expect-kg circuit-open 图故障态：runtime.kg 应 kg=neo4j 且 circuit=open（需先触发一次失败查询）
// 退出码 0=全部符合预期；1=有断言不符（可直接串进门禁脚本）。
//
// 前置：dev 后端起在 :8080，dev 库 admin 已提权（role=ADMIN）。
import { argv, exit } from 'node:process';

function apiVal(name) {
  const i = argv.indexOf(name);
  return i > 0 ? argv[i + 1] : '';
}

const BASE = (apiVal('--base') || 'http://localhost:8080') + '/api/v1';
const EXPECT = apiVal('--expect-kg') || 'any';
const PW = 'Passw0rd!2026';

const failures = [];
const check = (ok, msg) => {
  console.log(`${ok ? '✓' : '✗'} ${msg}`);
  if (!ok) failures.push(msg);
};

async function login() {
  const r = await fetch(BASE + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PW }),
  }).then(x => x.json()).catch(() => null);
  return r?.data?.accessToken || '';
}

async function get(path, token) {
  const res = await fetch(BASE + path, { headers: { Authorization: `Bearer ${token}` } });
  const json = await res.json().catch(() => null);
  return { status: res.status, body: json };
}

const token = await login();
if (!token) {
  console.error('✗ admin 登录失败：请确认 dev 库 admin 已提权且口令为约定值');
  exit(1);
}
console.log(`· 已以 admin 登录 ${BASE}，期望 KG 口径：${EXPECT}`);

// ─────────────────────── G8：runtime 现值快照 ───────────────────────
// 时序说明：`describe()` 刻意只看进程内状态、不触网（观测动作本身不能变成对图库的压力），
// 而图侧的懒同步由"首次 KG 查询"触发。所以若不先让 KG 真跑一次，冷进程的 runtime.kg 只会
// 显示 syncedVersion=none——那是"还没查过"的如实反映，不是缺陷。这里按真实运维顺序先预热。
if (EXPECT === 'neo4j' || EXPECT === 'circuit-open') {
  // 冷态探针：证明 describe() 真的不触网——进程尚未查过图时如实报 syncedVersion=none，
  // 而不是"顺便查一把图把版本号填上"。这一步把该设计要点变成可复跑的证据。
  const cold = await get('/admin/metrics/overview?days=7', token);
  console.log(`· 冷态探针（预热前）runtime.kg = ${cold.body?.data?.runtime?.kg ?? '(缺)'}`);
  await get('/admin/content/kg/graph-audit', token);   // 预热：走一次图读路径，触发 ensureSynced
}

const ov = await get('/admin/metrics/overview?days=7', token);
check(ov.status === 200 && ov.body?.code === 0,
  `/admin/metrics/overview 应 200/code0，实为 ${ov.status}/${ov.body?.code}`);
const runtime = ov.body?.data?.runtime ?? {};
const kg = String(runtime.kg ?? '');
console.log(`· runtime.kg      = ${kg || '(缺)'}`);
console.log(`· runtime.llmGate = ${runtime.llmGate ?? '(缺)'}`);
check(kg.length > 0, 'G8：runtime.kg 应存在（与 llmGate 同一处可见）');

if (EXPECT === 'db') {
  check(kg === 'kg=db(kg_node)', `期望 db 口径 kg=db(kg_node)，实为 ${kg}`);
} else if (EXPECT === 'neo4j') {
  check(kg.startsWith('kg=neo4j'), `期望图口径 kg=neo4j...，实为 ${kg}`);
  check(!kg.includes('syncedVersion=none'),
    `预热一次图查询后应已同步（describe 不触网，故必须实际查过图才会回版本号），实为 ${kg}`);
  check(kg.includes('circuit=closed'), `期望熔断闭合，实为 ${kg}`);
} else if (EXPECT === 'circuit-open') {
  check(kg.startsWith('kg=neo4j') && kg.includes('circuit=open'), `期望熔断开态，实为 ${kg}`);
}

// ─────────────────────── G9：DB ↔ 图对账 ───────────────────────
const au = await get('/admin/content/kg/graph-audit', token);
check(au.status === 200 && au.body?.code === 0,
  `/admin/content/kg/graph-audit 应 200/code0，实为 ${au.status}/${au.body?.code}`);
const a = au.body?.data ?? {};
console.log(`· graph-audit: available=${a.available} consistent=${a.consistent} `
  + `contentVersion=${a.contentVersion} syncedVersion=${a.syncedVersion}`);

if (EXPECT === 'db') {
  check(a.available === false, '图未启用时对账应如实返回 available=false（不编造一致结论）');
  check(String(a.reason ?? '').includes('未启用'), `reason 应说明图未启用，实为 ${a.reason}`);
} else if (EXPECT === 'neo4j') {
  check(a.available === true, '图可用时对账应 available=true');
  check(a.consistent === true,
    `图应与 kg_node 一致，实报 nodes=${JSON.stringify(a.nodes)} relations=${JSON.stringify(a.relations)}`);
  for (const row of a.nodes ?? []) {
    const reserved = row.reserved ?? [];
    console.log(`   - ${String(row.label).padEnd(20)}[${row.kind}] expect=${row.expect} actual=${row.actual} `
      + `missing=${(row.missing ?? []).length} extra=${(row.extra ?? []).length}`
      + `${reserved.length ? ` reserved=${JSON.stringify(reserved)}` : ''}`);
  }
  for (const row of a.relations ?? []) {
    console.log(`   - ${String(row.type).padEnd(20)} expect=${row.expect} actual=${row.actual}`);
  }
}

console.log(failures.length ? `\n✗ 未通过 ${failures.length} 项` : '\n✓ 全部符合预期');
exit(failures.length ? 1 : 0);
