// 走查/压测脚本的统一口令来源。
//
// 安全审查 B-01：deploy/tools/*.mjs 曾在源码里写死一个测试口令，而它恰好等于本地/演练库里的 admin 口令——
// 仓库一旦公开，等于把管理后台口令一起公开。现在改为只从环境变量读取，仓库不保存任何可用口令。
//
// 用法：SV_E2E_PASS='<你的测试口令>' node deploy/tools/smoke_m9.mjs
const PW = process.env.SV_E2E_PASS;
if (!PW) {
  console.error('[tools] 缺少环境变量 SV_E2E_PASS（测试账号口令）。仓库不再内置口令，请先导出再运行。');
  process.exit(1);
}

// 管理端账号名同样可配：不同环境不一定都叫 admin
const ADMIN = process.env.SV_E2E_ADMIN || 'admin';

export { PW, ADMIN };
