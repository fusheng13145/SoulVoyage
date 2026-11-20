# docs/baseline 资产归属说明

> 2026-11-20 安全与质量审查第 04 项（基线资产归属）整理后新增。
> 本目录**只存快照与证据，不参与构建、不被代码引用**；任何"契约真源"都不在这里。

## 1. 真源在哪（先看这张表，别拿快照当契约）

| 东西 | 唯一真源 | 校验方式 |
|---|---|---|
| HTTP 契约（路径/模型/状态码） | `soul-backend/openapi/openapi.json`（**107 条路径**） | `OpenApiContractTest` 钉住；`npm run gen:api` 从它生成两端类型 |
| 数据库结构 | `soul-backend/src/main/resources/db/migration/V1__baseline.sql` + 后续 `V*.sql` | Flyway 启动即校验 |
| 后端测试基线 | 每次 `mvn -B test` 的真实输出 | 本目录 `reports/surefire-summary.md` 只是某次快照 |
| 前端逻辑覆盖率 | `npm run coverage` | 本目录 `reports/coverage-summary.json` 只是某次快照 |

## 2. 本目录各资产是什么

| 路径 | 性质 | 说明 |
|---|---|---|
| `manifest.md` | 清单 | Batch 0 基线身份、采集环境与复跑命令 |
| `openapi-batch0-snapshot.json` | **历史快照** | Batch 0（2026-11-11，`bc26bbe`）时的契约副本，**94 条路径**，比现网少 13 条。原文件名 `openapi.json` 容易让人误以为是契约源，2026-11-20 改名并在此声明 |
| `schema.sql` | 只读副本 | `deploy/sql/schema.sql` 的副本，便于离线对照 |
| `schema.web.d.ts` / `schema.miniprogram.d.ts` | 生成物副本 | 采集时两端 `src/api/schema.d.ts` 的副本，用于查端侧漂移 |
| `golden/` | 运行时 golden | 15 个场景的脱敏响应，采集脚本 `deploy/tools/snapshot_batch0.mjs` |
| `screenshots/`、`batch2-shots/` | 视觉基线 | 4 宽度 × 明暗 × 7 路由，采集脚本 `deploy/tools/snapshots_batch0.mjs` |
| `reports/surefire-summary.md`、`coverage-summary.json` | 测试快照 | 某一轮回归的结果，非实时指标 |
| `reports/load_m*_results.json` | **压测证据（历史）** | 原在 `deploy/`（脚本默认输出目录），2026-11-20 迁到此处：它们是验收证据而不是部署产物。口径是本机单实例 + mock/桩下游，不代表生产 |
| `c-modules.txt` | 守卫清单 | `deploy/tools/check_c_guard.mjs` 读取的 C 类禁止改动模块清单 |

## 3. 约定

1. **不要把本目录的快照接进任何脚本/CI 作为输入**（`c-modules.txt` 是唯一例外，它按设计被守卫脚本读取）。
2. 新增压测结果直接写到 `reports/`：`node deploy/tools/load_m9.mjs --out docs/baseline/reports/load_m9_results.json`。
3. 契约变了只改 `soul-backend/openapi/openapi.json` 并重新 `gen:api`；本目录的快照**不跟着更新**——它的价值就是"当时的样子"。
4. 想删旧快照前先确认没有文档引用：`rg "baseline/" --glob '!node_modules'`。
