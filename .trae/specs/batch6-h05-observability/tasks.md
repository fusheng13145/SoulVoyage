# H-05 Tasks

- [x] Task 1: 指标服务重构
  - [x] 1.1 新增 `AdminMetricsService`，承接 overview 聚合
  - [x] 1.2 `AdminMetricsController` 只依赖 service（去除 Repository 依赖）

- [x] Task 2: 慢请求记录
  - [x] 2.1 `SlowRequestRecorder`（环形缓冲 500，≥阈值才记）
  - [x] 2.2 `SlowRequestFilter`（排除 actuator/护栏自身/query）
  - [x] 2.3 配置 `soulvoyage.observability.slow-request-ms`

- [x] Task 3: 护栏聚合
  - [x] 3.1 失败告警：24h 失败率 + 最近 5 条失败
  - [x] 3.2 成本预算：token/估算成本/占用率/分级
  - [x] 3.3 质量抽检：近 7 天 20 份报告 feedback 抽样
  - [x] 3.4 `GET /admin/metrics/guardrails` + DTO
  - [x] 3.5 `AdminMetricsServiceTest`/`SlowRequestRecorderTest` 覆盖阈值与空态

- [x] Task 4: 契约更新
  - [x] 4.1 OpenAPI 快照更新并审阅（纯新增 250 行）
  - [x] 4.2 前端/小程序 `npm run gen:api`

- [x] Task 5: 管理端 UI
  - [x] 5.1 `api/admin.ts` guardrails 类型与接口
  - [x] 5.2 `AdminHomeView.vue` 失败告警/预算/抽检/慢请求四块

- [x] Task 6: 门禁与收口
  - [x] 6.1 后端 470 例；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
  - [x] 6.2 golden 15 场景字段路径一致；C guard PASS
  - [x] 6.3 更新基线 tasks.md 11.5 与本变更单落地记录

# Task Dependencies

- Task 3 依赖 Task 1–2。
- Task 4 依赖 Task 3。
- Task 5 依赖 Task 4。
- Task 6 依赖 Task 1–5。
