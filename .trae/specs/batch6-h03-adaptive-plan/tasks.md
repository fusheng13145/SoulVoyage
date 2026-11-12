# H-03 Tasks

- [x] Task 1: 后端计划自适应
  - [x] 1.1 `PlanDtos` 扩展 PlanView（adaptation/completionRate/overdueCount/remainingCount/nextScheduledDate/todaySkipped）
  - [x] 1.2 `PlanService` 建议规则：逾期顺延/低完成率减量/低情绪替换；危机态抑制
  - [x] 1.3 `PlanService.adapt` 应用 POSTPONE/REDUCE/REPLACE
  - [x] 1.4 `PlanService.skipToday` 今日未完成项顺延，幂等
  - [x] 1.5 `PlanController` 新增 `POST /{id}/adapt`、`POST /{id}/skip-today`
  - [x] 1.6 新增 `PlanAdaptationTest`（建议/应用/幂等/越权/危机态）

- [x] Task 2: 契约更新
  - [x] 2.1 更新 OpenAPI 快照并审阅 diff
  - [x] 2.2 前端/小程序 `npm run gen:api`

- [x] Task 3: Web 自适应入口
  - [x] 3.1 `planApi.adapt/skipToday` + `PlanAdaptationView` 类型
  - [x] 3.2 `TodayView` 今日不做 + 顺延提示 + 建议轻提示
  - [x] 3.3 `PracticeView` 建议横幅 + 今天不做 + 操作后刷新

- [x] Task 4: 门禁与收口
  - [x] 4.1 后端 449 例；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
  - [x] 4.2 golden 10-plan 新增字段审阅；其余场景字段路径不变；C guard PASS
  - [x] 4.3 更新基线 tasks.md 11.3 与本变更单落地记录

# Task Dependencies

- Task 2 依赖 Task 1。
- Task 3 依赖 Task 2。
- Task 4 依赖 Task 1–3。
