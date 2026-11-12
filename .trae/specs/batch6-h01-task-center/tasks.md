# H-01 Tasks

- [x] Task 1: 后端任务中心接口
  - [x] 1.1 `TaskTimelineStep` + `GET /tasks/{taskNo}/steps`（属主断言、升序时间线）
  - [x] 1.2 `POST /tasks/{taskNo}/retry`（仅 FAILED、属主、审计 TASK_RETRY）
  - [x] 1.3 新增 `UserTaskCenterTest`（属主/越权/非 FAILED 拒绝/成功链路）

- [x] Task 2: 契约更新
  - [x] 2.1 `mvn test -Dtest=OpenApiContractTest -Dopenapi.update=true` 更新快照并审阅 diff（纯新增 105 行）
  - [x] 2.2 前端/小程序 `npm run gen:api` 同步生成类型

- [x] Task 3: Web 任务中心
  - [x] 3.1 `taskApi.steps/retry` + 列表过滤参数
  - [x] 3.2 `TasksView.vue`：历史列表、步骤时间线、失败原因、重试、实时跟随
  - [x] 3.3 路由 `/tasks` + MeView 入口 + CANCELLED/EXPIRED 文案

- [x] Task 4: 门禁与收口
  - [x] 4.1 后端全量测试（438 例）、前端 112 例、typecheck 与 build
  - [x] 4.2 golden 15 场景、C guard
  - [x] 4.3 更新基线 tasks.md 11.1 / checklist.md G9 状态

# Task Dependencies

- Task 2 依赖 Task 1（接口形状先定）。
- Task 3 依赖 Task 2（生成类型）。
- Task 4 依赖 Task 1–3。
