# H-01 任务中心 2.0 变更单

> 上位基准：`.trae/specs/establish-soul-voyage-baseline/spec.md` §4.2 H-01；基线 tasks.md Task 11.1。
> 性质：扩展功能（非重构）。**不改任务语义**：仅新增查询/重试入口与展示层。
> 范围：后端 API + Web 任务中心；小程序本项不做（端侧暂无任务入口，后续按需单独变更）。

## 1. 目标

让用户能在一个页面看清「系统为我做了什么」：统一任务历史、失败原因、步骤时间线、断点重放（SSE 重连补发）与失败重试入口。

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `TaskController` | 只有提交/列表/详情/流/取消；无步骤时间线、无用户侧重试（重试仅 `AdminTaskController`） |
| `task_step_log` | 已有 stepSeq/agentCode/status/attempt/costMs/errorMsg/createdAt，可直接做时间线 |
| SSE | `TaskEventBus` 已支持 `Last-Event-ID` 环形缓冲补发；前端 `streamTask` 已实现断线重连与轮询降级 |
| Web | 无任务中心页面；任务状态只在日记/模拟等页面局部出现 |

## 3. 改动

### 3.1 后端（不新增表、不改状态机）

| 接口 | 行为 |
|---|---|
| `GET /api/v1/tasks/{taskNo}/steps` | 属主断言；返回步骤时间线（stepSeq、agent、stepCode、status、attempt、costMs、errorMsg、createdAt），按 stepSeq/id 升序 |
| `POST /api/v1/tasks/{taskNo}/retry` | 属主断言；仅 `FAILED` 可重试（否则 400/2001）；复用 `OrchestratorService.retry` 的断点续跑；审计 `TASK_RETRY`；返回 `{taskNo,status:"RUNNING"}`（与 AdminTask 重试回执一致） |

- DTO 收敛在 `orchestrator.TaskStepView`（Controller 不新增 domain 依赖），不暴露 token 等运营字段。
- 不存在的行为变化：列表/详情/流/取消语义不动；重试仍从首个失败步续接、复用已落库中间产物。

### 3.2 Web

| 文件 | 改动 |
|---|---|
| `api/domains/task.ts` | 新增 `steps(taskNo)`、`retry(taskNo)`；`list` 支持 `pipelineCode/status` 可选过滤 |
| `views/TasksView.vue`（新增） | 任务历史列表（管线、状态、时间、失败原因）；点开抽屉看步骤时间线；`FAILED` 显示重试；`PENDING/RUNNING` 可实时跟随（复用 `streamTask`，事件到达即刷新时间线，终态自动停止） |
| `router` + `MeView` | 新增 `/tasks` 路由与「任务中心」入口 |
| `constants/task.ts` | 补 `CANCELLED/EXPIRED` 状态文案 |

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 接口 | `GET steps` 属主 200 / 他人 403；`POST retry` 非 FAILED → 400/2001，FAILED → 返回 RUNNING |
| 契约 | OpenAPI 快照按预期更新并审阅；前端/小程序 `gen:api` 同步且零意外 diff |
| 前端 | 任务中心可列表、看时间线、重试、实时跟随；既有 112 例与 build 全绿 |
| 回归 | 后端全量测试绿；golden 15 场景字段路径一致；C guard PASS |
| 回滚 | 仅新增接口与页面；回滚删除路由/页面即可，无数据迁移 |

## 5. 批准状态

- [x] 用户「开始 Batch 6，从 H-01 开始」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 后端 | `TaskTimelineStep` + `GET /steps`、`POST /retry`；重试校验与列表查询下沉到 `OrchestratorService`，Controller 不再依赖 Repository（B-18 存量违规顺手清偿） |
| 契约 | OpenAPI 快照纯新增 105 行；`retryTask` 命名避开与 Admin `retry` 的 operationId 冲突；两端生成类型已同步 |
| Web | `TasksView.vue` + `/tasks` 路由 + Me 入口；支持筛选、时间线、失败原因、重试与实时跟随（SSE 断线重放沿用 Last-Event-ID） |
| 测试 | 后端 60 套件 / 438 例 0 失败；前端 112 例 + typecheck + build；小程序 20 例 + typecheck/lint |
| 回归 | golden 15 场景字段路径一致；C guard PASS（210 个变更路径，C 目录零改动） |
| 说明 | ArchUnit 冻结清单在 Controller 去 Repository 依赖后重新生成；`archunit_store/` 需随代码提交 |
