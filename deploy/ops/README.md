# deploy/ops 运维核查脚本

> 2026-11-20 安全与质量审查第 05 项（"生产库是否有 admin + 旧测试口令账号"）落地物。

## 1. 为什么需要它

代码层面只能证明**新库不会自动长出管理员**（`Flyway` 首启只 baseline + `ContentDataSeeder` 只种内容，不种用户；`RbacSeeder` 只写权限码不建人），
但**生产库是既有实例，里面有没有人用 `admin` 建过号、有没有跑测试/压测脚本留下的账号，代码看不出来**——只能对库查一次。

## 2. 上手

```powershell
# 只读账号即可；脚本里没有 UPDATE/DELETE
mysql -h <host> -P <port> -u <readonly_user> -p soul_voyage < deploy/ops/check_prod_accounts.sql
```

建议把输出留档（`docs/baseline/reports/` 或你的上线记录），核查结论按下表填：

| 检查段 | 期望结果 | 不达标怎么办 |
|---|---|---|
| 1) 角色分布 | `ADMIN` 数量 = 你实际建的运维账号数（通常 1） | 多出来的逐个看第 2 段，确认后 `UPDATE role='USER'` 降级 |
| 2) 管理员明细 | 每一条都能说出"谁、什么时候、为什么建" | 说不出来的：先 `status=2` 冻结，再决定删号/降级 |
| 3) 疑似测试账号 | **0 条** | 命中即冻结；若是压测遗留，确认无业务数据后按注销流程清理 |
| 4) 近 30 天新账号 | 只有你自己或真实用户 | 陌生人账号看 `created_at` 与来源 IP 日志（`audit_log`） |
| 5) 微信绑定 | 0，或等于你预期的绑定数 | 非预期绑定说明 openid 已被用过，核对 `wx_openid` 归属 |

## 3. 关于"旧测试口令 `Passw0rd!2026`"

- 结论**不能从库里查**：口令是 `password_hash VARCHAR(128)` 的 BCrypt 哈希，不可逆，也无法反查"是不是这个口令"。
- 也不要"拿旧口令去试登录"来验证：会累加失败计数、触发 IP/账号维度限流，甚至把真实管理员锁在门外；且失败与否不能证明别的账号是否也用同一口令。
- 正确顺序：**先按第 3 段把可疑用户名冻结（`status=2` 后任何口令都登不进来），再走应用接口改密**（`PUT /api/v1/account/password` 或管理端「用户支持」重置）。
- 仓库内曾散落旧测试口令字符串，2026-11-20 已清理：29 个后端测试改用 `SvFixture!2026`，`docs/baseline/golden/1-auth-register.json` 与 `2-auth-login.json` 的 `password` 已脱敏为 `<redacted>`，`snapshot_batch0.mjs` 脱敏清单补 `password`；仅审查报告正文保留历史叙述。`deploy/tools/*.mjs` 早已改为只读 `SV_E2E_PASS`，运行时不读任何硬编码口令。

## 4. 定期化（可选）

把第 1、2 段做成上线流水线的前置检查（发布前跑一次、输出贴进发布记录），第 3 段每季度跑一次。
若以后接入监控，可把 `role='ADMIN'` 的计数做成告警项：数量变化即通知。
