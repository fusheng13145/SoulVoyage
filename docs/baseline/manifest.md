# soul-voyage 基线快照清单（Batch 0）

> 依据：`.trae/specs/establish-soul-voyage-baseline/spec.md` §3.6 与 `.trae/documents/batch0-batch1-baseline-refactor.md` Phase A
> 目的：为 Batch 1 及后续重构提供可复跑、可对照的静态契约、运行时 golden 与视觉基线。
> 本目录只存放快照与说明，不参与构建。

## 1. 基线身份

| 项 | 值 |
|---|---|
| Git 提交 | `bc26bbe` fix(delivery): 验收修复——小程序契约同步 + TS 擦除语法合规 + 构建残留忽略 |
| 快照日期 | 2026-11-11（Asia/Shanghai） |
| 采集主机 | Windows 11 家庭版（中文） |
| JDK | 21.0.6 LTS |
| Node | v24.15.0 |
| Maven | 3.9.15 |
| 后端 | `soul-backend/target/soul-backend-0.1.0.jar`（2026-09-29 构建，晚于全部后端主源码，未重建） |
| 数据库 | 本机 MySQL 8.x @ `localhost:3306`，Redis 7 @ `localhost:6379` |
| 前端 | Vite dev @ `http://localhost:5174`（`npm run dev -- --port 5174 --strictPort`） |
| 后端端口 | `http://localhost:8080`（`/actuator/health` 就绪） |
| LLM/ASR | `SV_LLM_PROVIDER=mock`（本地 env），产物含「（mock）」前缀属预期 |

## 2. 静态快照（提交入库）

| 文件 | 来源 | SHA-256 |
|---|---|---|
| `openapi-batch0-snapshot.json`（原 `openapi.json`） | `soul-backend/openapi/openapi.json`（Batch 0 时的副本） | `25FBFA93532CA7E00915D40BBB8A9F5F037BD201E4A0B2E2E6AF380C7BF4639C` |
| `schema.sql` | `deploy/sql/schema.sql`（只读副本，不改语义） | `545C44325A71A824349153B70B7B35E3ADADEAD035DD2C94EEB0B761C1444505` |
| `schema.web.d.ts` | `soul-frontend/src/api/schema.d.ts` | `7EC6B01076228A1EBBEE1E10963CFFFAAEB69FEA197EBE4D7C63C2422ACADC0D` |
| `schema.miniprogram.d.ts` | `miniprogram/src/api/schema.d.ts` | `7EC6B01076228A1EBBEE1E10963CFFFAAEB69FEA197EBE4D7C63C2422ACADC0D` |

- Web 与小程序两份生成类型哈希一致：当前契约无端侧漂移。
- `c-modules.txt`：C 类禁止改动清单（与 Spec §3.4 一一对应），供 `deploy/tools/check_c_guard.mjs` 读取。

## 3. 测试与覆盖率基线

| 项 | 值 | 来源 |
|---|---|---|
| 后端测试类 | 47 | `reports/surefire-summary.md` |
| 后端 Tests run | 401 | 同上 |
| 后端 Failures / Errors | 0 / 0 | 同上 |
| 后端 Skipped | 9（`Neo4jKgContractTest`，无真图实例） | 同上 |
| 前端覆盖率 | 100%（lines/branches/functions/statements，逻辑层口径） | `reports/coverage-summary.json` |
| 压测/负载快照 | M9/M10/M18 四份 JSON（历史证据，非当前口径） | `reports/load_m9_results.json`、`load_m10_results.json`、`load_m18_db_results.json`、`load_m18_graph_results.json` |

## 4. 运行时 golden（15 个场景）

采集脚本：`node deploy/tools/snapshot_batch0.mjs`（可复跑；每次生成新的唯一测试账号）。

| # | 文件 | 覆盖内容 | 结果 |
|---|---|---|---|
| 1 | `golden/1-auth-register.json` | 注册 → 令牌对 | PASS |
| 2 | `golden/2-auth-login.json` | 口令登录 | PASS |
| 3 | `golden/3-auth-refresh.json` | refresh 轮换 | PASS |
| 4 | `golden/4-auth-logout.json` | 登出吊销 | PASS |
| 5 | `golden/5-diary-task.json` | DIARY_PIPELINE 提交 → 轮询终态 → 任务流各步产物 | PASS（SUCCESS，4 Agent） |
| 6 | `golden/6-risk-receipt.json` | 高危日记 → RISK_ARCHIVE 回执 + 转介资源 | PASS（riskLevel=HIGH） |
| 7 | `golden/7-simulation.json` | 开场景 → 一轮 SSE → finish → 任务终态 | PASS |
| 8 | `golden/8-companion.json` | 开会话 → 一轮 SSE → 收段 → 任务终态 | PASS |
| 9 | `golden/9-checkin.json` | 打卡 → 补签 → streak → 月视图 | PASS |
| 10 | `golden/10-plan.json` | `/plans/active` + `/plans/{id}` | PASS（疏导已生成 3 天计划） |
| 11 | `golden/11-report.json` | 报告列表 → 详情 → 反馈 → 回读 | PASS |
| 12 | `golden/12-archive.json` | 档案汇总 → 导出 → 一次性领取 | PASS |
| 13 | `golden/13-account.json` | 注销申请（令牌全吊销）→ 冷静期重登 → 撤回 → 恢复 | PASS |
| 14 | `golden/14-admin-task.json` | 管理端任务列表 + 详情 | PASS |
| 15 | `golden/15-board-suppression.json` | 群体看板 <10 人抑制 | PASS（`suppressed=true`） |

**SKIPPED（如实记录，不伪造）**

- `14-admin-task` 的失败任务「手动重跑」：当前列表无 `FAILED` 任务，未触发 `POST /admin/tasks/{taskNo}/retry`。接口存在性已由代码与 OpenAPI 快照覆盖。

**Spec 事实修正**

- Spec §3.6 写的 `risk/receipt` 端点在当前实现中不存在。风险回执实际来自任务流 `middle_result`（`agent=RISK_ARCHIVE`）与 `done.payload`；脚本按此采集，`/risk/resources` 单独采集转介资源。

## 5. 视觉基线（56 张）

采集脚本：`node deploy/tools/snapshots_batch0.mjs`（puppeteer-core + 本机 Edge）。

- 路由：`/today`、`/diaries`、`/diaries/write`、`/insights`、`/practice`、`/me`、`/login`
- 宽度：375 / 768 / 1024 / 1440（deviceScaleFactor=1）
- 主题：light / dark（`localStorage.sv_theme`）
- 命名：`screenshots/{width}-{theme}-{page}.png`
- 数据：脚本注册专用账号，回填 7 天自评/打卡 + 1 篇日记（等待任务终态后再截图），保证页面非空态。
- 已知差异：`/insights` 图表为异步渲染，统一等待 1.6s；若后续对照出现图表空白，先排查等待时长而非视觉回归。

## 6. golden 对照规则（忽略字段）

采集时已做**键排序 + 脱敏**，以下键统一替换为 `"<redacted>"`（值为 `null` / `""` 时保留原值）：

- 身份/凭证：`id`、`userId`、`sessionId`、`simulateId`、`turnId`、`fileId`、`taskId`、`groupId`、`planId`、`riskEventId`、`accessToken`、`refreshToken`、`token`、`bindTicket`、`openid`、`wxOpenid`、`username`、`nickname`、`password`
- 随机/幂等：`taskNo`、`traceId`、`clientReqId`
- 时间：所有以 `At` 结尾的键（`createdAt`、`finishedAt`、`deletionRequestedAt`、`doneAt` 等）

对照原则：**业务字段全量比对**；上述字段只比结构与类型，不比具体值。新增忽略字段必须在本文件登记后再使用。

## 7. 环境与已知限制

1. 本机 MySQL/Redis 必须已启动；后端未启动时脚本以退出码 `2` 记 `BLOCKED`，不写入 golden。
2. 运行时场景会写入测试数据（唯一用户名 `base_*` / `base_ui_*`、1 个群体看板组）；不建表、不改 schema、不删既有数据。
3. 管理端场景使用本地 `admin` 账号；若该账号不可登录，14/15 文件会写 `status=SKIPPED` 并说明原因。
4. golden 中的报告/回执正文来自 mock LLM，仅用于「同环境前后对照」，不代表真实模型输出。
5. A-18 核验：`soul-backend/Dockerfile:16-17` 已定义 `HEALTHCHECK` 调用 `/actuator/health`，`deploy/docker-compose.yml` 的 `depends_on: service_healthy` 可正常工作；**Spec 中「compose 未定义 healthcheck」的描述已按事实修正，本次不改 compose**。
6. 快照不包含任何密钥；`deploy/local.env` 仅以环境变量注入进程，未打印、未复制。

## 8. 复跑命令

```powershell
# 1) C 类守卫（只读）
node deploy/tools/check_c_guard.mjs

# 2) 后端（先注入 deploy/local.env，不打印密钥）
java "-Dfile.encoding=UTF-8" -jar soul-backend/target/soul-backend-0.1.0.jar

# 3) 前端
cd soul-frontend; npm run dev -- --port 5174 --strictPort

# 4) golden + 截图
node deploy/tools/snapshot_batch0.mjs
node deploy/tools/snapshots_batch0.mjs
```

退出码约定：`check_c_guard.mjs` 0=PASS / 1=BLOCKED / 2=错误；`snapshot_batch0.mjs` 0=全部执行 / 1=存在失败场景 / 2=后端未就绪（BLOCKED）。

## 9. Batch 1 回归结果（2026-11-11）

| 命令 | 结果 |
|---|---|
| `cd soul-backend && mvn -B test` | BUILD SUCCESS；401 例，0 失败，9 跳过（与基线一致） |
| `cd soul-frontend && npm run lint` | 0 error |
| `cd soul-frontend && npm run format:check` | 通过 |
| `cd soul-frontend && npm run typecheck` | 通过（新增脚本） |
| `cd soul-frontend && npm run test` | 11 个 spec / 108 例全绿（基线 100 例 + 日期 5 + SSE 终态 2 + 主题跟随 1） |
| `cd soul-frontend && npm run coverage` | 逻辑层 100%（lines/branches/functions/statements） |
| `cd soul-frontend && npm run build` | 成功 |
| `cd soul-frontend && npm run gen:api` + `git diff --exit-code -- src/api/schema.d.ts` | 无契约漂移 |
| `cd miniprogram && npm run typecheck && build:mp-weixin && build:h5` | 全部通过（未改小程序） |
| `node deploy/tools/check_c_guard.mjs` | PASS，C 类零 diff |
| 视觉走查（375/1440，明暗） | 今日/日记/练习/洞察/我的正常；练习页计划条目恢复显示 |

## 10. Batch 0 发现的额外缺陷与处理

| 位置 | 现象 | 证据 | 建议 |
|---|---|---|---|
| `soul-frontend/src/views/PracticeView.vue` 原第 61 行 | `/plans/active` 返回 `planId="pl_N"`，页面直接拼 `/plans/pl_N`；后端 `PlanController` 只接受 `long`，请求 400，计划条目永远为空且整页报「加载失败」 | 基线截图 `screenshots/375-light-practice.png`；`ExerciseController.parsePlanId` 会剥 `pl_` 前缀，说明这是路径参数与 body 参数的口径不一致 | 已在 Batch 1 的 A-21 改造中一并修正为 `Number(planId.replace(/^pl_/, ''))`；属前端视图内修复，不改后端契约。若需回退 A-21，此行需同步回退 |

