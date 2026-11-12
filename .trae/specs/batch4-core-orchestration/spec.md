# Batch 4 实施 Spec：核心编排（B-03 / B-04 / B-11 / B-13）

> 上位基准：`.trae/specs/establish-soul-voyage-baseline/spec.md` §3.3、§3.6 Batch 4；
> 任务来源：`tasks.md` Task 9。
> 性质：B 类重构。**核心编排代码，改动前逐项书面批准；B-13 需专项批准，未批准不执行。**
> 行为等价红线：5 条流水线步骤顺序、SSE 事件名称/顺序/载荷键、任务状态值、错误码语义不变。

## 1. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `orchestrator/OrchestratorService.java`（295 行） | 提交、幂等、并发限制、执行循环、步骤重试、落库、SSE、指标、状态收口全部混在一个类；`nextConsumer(spec, seq)` 永远返回 `"ORCHESTRATOR"`（死抽象） |
| `orchestrator/AgentCode.java` | 已有枚举，但 `Agent.code()`、`StepSpec.agentCode`、`AgentRegistry` 仍用 `String` |
| `orchestrator/pipeline/TaskDefinition.code()` | 返回字符串字面量；5 个实现各自写 `"XXX_PIPELINE"`；`PipelineRegistry` 按字符串索引 |
| `orchestrator/api/TaskController.java:33,42-44` | 传输层直接注入 `DiaryService`，提交后同步调 `attachTask`；跨域耦合 |
| `TaskDtos.SubmitReq.payload` | `JsonNode` 无任何边界校验；未知 pipeline 在 `submit` 首行才抛 3002；缺 `simulateId/sessionId` 等要到异步步骤才失败 |
| `agent/risk/RiskArchiveAgent.java` | 风险事件、审计、危机迁移、周画像多次写库，类无事务边界；`updateWeeklyProfile` 内部吞异常（画像失败不得阻断） |
| SSE 事件契约 | `task_created / task_status / step_started / step_retry / middle_result / step_failed / done / error`（`TaskEventBus` 注释与调用点一致） |

已确认的调用方（`submit` 均以字符串流水线码调用，签名必须保留）：
`DiaryService.reanalyze`、`ExerciseController.cognitive`、`SimulationService.finish`、
`CompanionService.sealAndDigest`、`GrowthLetterService.sweep`、`TaskController.submit`、
`AdminTaskController.retry`；测试中 `orchestrator.submit(...)` 以字符串 mock（`ExerciseControllerTest`、`CompanionServiceTest`、`GrowthLetterServiceTest`）。

## 2. 目标架构

```
orchestrator/
  OrchestratorService        门面：submit/retry/byNo/finalPayload/cancel（签名不变）
  TaskSubmissionService      幂等 + 并发限制 + 落库 + task_created + 提交后钩子 + 派发
  TaskExecutionService       执行循环：危机上下文、步骤推进、降级、重试续接、终态
  TaskStepRunner             单步：REQUEST 落库、step_started、重试/退避、step 日志、middle_result
  TaskEventPublisher         SSE 事件唯一出口（事件名与载荷键集中定义）
  TaskStatus / TaskStateMachine  显式状态机（值不变，仅收敛迁移判定）
  PipelineCode              5 条流水线枚举
  AgentCode                 已有，改为强类型贯穿
  ApplicationHook           TaskSubmissionHook（B-11 用）
```

依赖方向：`api → OrchestratorService → Submission/Execution → StepRunner → AgentRegistry`；
`domain/diary → TaskSubmissionHook 实现`，`Controller` 不再依赖 `domain.diary`。

## 3. 分阶段改动单

### Stage 4A — B-04：枚举与边界校验（不改执行语义）

| 文件 | 改动 |
|---|---|
| 新增 `orchestrator/PipelineCode.java` | `DIARY_PIPELINE, COGNITIVE_PIPELINE, SIMULATE_PIPELINE, COMPANION_PIPELINE, GROWTH_LETTER_PIPELINE`；`static PipelineCode from(String)`：空/未知 → `BizException(PIPELINE_NOT_ALLOWED, "未注册的流水线: " + 原值)` |
| `orchestrator/pipeline/TaskDefinition.java` | `String code()` → `PipelineCode code()`；新增 `default void validatePayload(JsonNode payload)` |
| 5 个 `*Pipeline.java` | `code()` 返回枚举常量；实现 `validatePayload`（见下表） |
| `orchestrator/pipeline/StepSpec.java` | `record StepSpec(String stepCode, AgentCode agentCode, String outputSchema)` |
| `orchestrator/agent/Agent.java` + 7 个 Agent | `String code()` → `AgentCode code()`，实现返回枚举 |
| `orchestrator/AgentRegistry.java` | `Map<AgentCode, Agent>`；`require(AgentCode)`；构造时重复码显式报错；`@PostConstruct` 校验所有流水线步骤的 Agent 均可解析 |
| `orchestrator/pipeline/PipelineRegistry.java` | `Map<PipelineCode, TaskDefinition>`；`require(PipelineCode)` / `require(String)`；`@PostConstruct` 校验「枚举值全覆盖 + 每条流水线在合成上下文下 steps 非空」 |
| `orchestrator/OrchestratorService.java` | `submit(String)` 首行 `PipelineCode code = PipelineCode.from(...)`；`pipelines.require(code).validatePayload(input)`；`pipelineCode` 落库用 `code.name()`；事件/日志/协议处 `spec.agentCode().name()` |
| `soul-backend/src/test/.../M9DualTrackMatrixTest.java:72` | `new StepSpec("risk", AgentCode.RISK_ARCHIVE, ...)` |

payload 校验（只校验「结构必需项 / 类型」，不新增业务规则）：

| 流水线 | 规则 | 失败 |
|---|---|---|
| `DIARY_PIPELINE` / `COGNITIVE_PIPELINE` | `payload` 必须为对象；`diaryText` 存在时必须是文本 | 2001 |
| `SIMULATE_PIPELINE` | 必须为对象；`simulateId` 为正整数 | 2001 |
| `COMPANION_PIPELINE` | 必须为对象；`sessionId` 为正整数 | 2001 |
| `GROWTH_LETTER_PIPELINE` | 必须为对象；`statWeek` 非空文本；`material.dataPoints` 为 ≥1 整数 | 2001 |

> 说明：`diaryText` 为空串仍放行（保持 `attachTask` 静默跳过与异步降级的既有语义），只拦截缺字段/错类型。

新增测试 `PipelineContractTest`：
- 5 个枚举值与 5 个 `TaskDefinition` 一一对应；缺任一实现启动失败。
- 未知流水线 `POST /tasks` → HTTP 400 / code 3002。
- `SIMULATE_PIPELINE` 缺 `simulateId`、`COMPANION_PIPELINE` 缺 `sessionId` → HTTP 400 / code 2001，且不产生任务行。

### Stage 4B — B-03：编排拆分与显式状态机

| 文件 | 改动 |
|---|---|
| 新增 `orchestrator/TaskStatus.java` | `PENDING, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED`；`static TaskStatus of(String)` 容错；`boolean terminal()` |
| 新增 `orchestrator/TaskStateMachine.java` | 允许：PENDING→RUNNING/CANCELLED；RUNNING→SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED；终态不可再迁移。非法迁移抛 `IllegalStateException`（仅内部 bug，不映射为用户错误码） |
| 新增 `orchestrator/TaskEventPublisher.java` | 固定方法：`created(task)`、`status(taskNo,status)`、`stepStarted`、`stepRetry`、`middleResult`、`stepFailed`、`done(taskNo,status,payload)`、`error(taskNo)`；载荷键与现状逐字一致 |
| 新增 `orchestrator/TaskSubmissionService.java` | 从 `submit/retry` 迁入：clientReqId 幂等、单用户 2 活跃上限、`sv.task.submit` 计数、`task_created`、虚拟线程派发、重跑起点与原始输入还原 |
| 新增 `orchestrator/TaskExecutionService.java` | 从 `run` 迁入：RUNNING 迁移、危机上下文、步骤循环、`promoteDiaryText`、DEGRADED/PARTIAL_SUCCESS、FINAL 落库、失败收口、取消检查、终态发布 |
| 新增 `orchestrator/TaskStepRunner.java` | 从 `executeStep` 迁入：REQUEST 落库、step_started、3 次尝试与 2s/8s 退避、step_retry、SUCCESS/FAILED 日志与指标、middle_result、LLM 用量归集 |
| `orchestrator/OrchestratorService.java` | 改为门面：`submit/retry` 委托 Submission，`byNo/finalPayload` 保留查询，`cancel` 走状态机；删除 `nextConsumer`，协议上游固定 `AgentCode.ORCHESTRATOR.name()` |
| `soul-backend/src/test/.../OrchestratorEventSequenceTest.java`（新增） | 假 Agent + 假 TaskDefinition 断言事件序列与状态：`task_created → task_status(RUNNING) → step_started → middle_result → task_status(SUCCESS) → done`；输出校验失败时为 `step_failed → task_status(PARTIAL_SUCCESS) → done`；取消为 `task_status(CANCELLED) → done` |

约束：
- `TaskInstanceEntity.status` 仍是 `String`，DB 值不变；状态机只做写入前判定。
- 事务边界不变：执行循环仍不持有数据库事务；`submit` 仍同步落库后派发。
- `MAX_ATTEMPTS=3`、`BACKOFF_MS={2000,8000}`、`abbreviate(500)` 原值保留。

### Stage 4C — B-11：Controller 与 DiaryService 解耦

| 文件 | 改动 |
|---|---|
| 新增 `orchestrator/TaskSubmissionHook.java` | `PipelineCode pipeline();` + `void afterSubmit(long userId, JsonNode payload, TaskInstanceEntity task);` |
| 新增 `domain/diary/DiarySubmissionHook.java` | 实现 hook，仅对 `DIARY_PIPELINE` 调用 `DiaryService.attachTask(...)` |
| `orchestrator/TaskSubmissionService.java` | 注入 `List<TaskSubmissionHook>`；落库后、派发前调用匹配 hook |
| `orchestrator/api/TaskController.java` | 删除 `DiaryService` 字段与 `if ("DIARY_PIPELINE"...)` 分支；只调用 `orchestrator.submit(...)` |

语义说明（有意改良，需在变更记录中声明）：现状是「先派发虚拟线程、再写日记」，新顺序是「先写日记（`@Transactional` 提交）、再派发」，保证流水线启动时日记行已存在；`clientReqId` 幂等重放仍不会重复写（`attachTask` 已有 `findByTaskIdAndUserId` 去重）。
新增测试 `DiaryTaskSubmissionTest`：提交成功后立刻可查到日记行；同 `clientReqId` 重放只 1 行 + 同 `taskNo`。

### Stage 4D — B-13：风险归档事务边界（**专项批准后才执行**）

| 文件 | 改动 |
|---|---|
| 新增 `agent/risk/RiskArchiveService.java` | `@Transactional record(...)`：风险事件落库 + 审计 + `sv.risk.event` 计数，作为一个原子单元 |
| 新增 `agent/risk/RiskArchiveService.java`（同文件） | 周画像 `updateWeeklyProfile` 保持「失败不阻断」：独立事务、异常在服务内吞掉并记 `profileUpdated=false` |
| `agent/risk/RiskArchiveAgent.java` | 保留规则轨/语义轨判定与 receipt 组装；持久化委托 `RiskArchiveService`；`CrisisService.onHighRisk` 调用位置与事务参与方式不变（**C 类语义零改动**） |

顺序保持：规则轨 → 语义轨 → 风险事件/审计 → 危机迁移 → 周画像。
幂等保持：不改表结构、不加唯一键，沿用「每任务每触发一条事件」的现状。

## 4. 验收标准

| 门禁 | 通过条件 |
|---|---|
| 后端单测 | `mvn -q clean test` 全绿（现有 407 例 + 新增用例） |
| 契约 | `OpenApiContractTest` 通过；本批不改接口形状，`openapi.json` 零 diff |
| SSE | `TaskEventBusTest` + 新增 `OrchestratorEventSequenceTest`；5 条流水线事件名/顺序与 §1 一致 |
| 业务等价 | 起后端后 `node deploy/tools/snapshot_batch0.mjs`；15 个 golden 场景字段路径逐项一致（仅允许脱敏值差异） |
| 风险等价 | 5 类风险矩阵（`M9DualTrackMatrixTest`）全绿；危机状态、事件数、触发类型不变 |
| 前端/小程序 | `npm run gen:api` 零 diff；前端既有 108 例、小程序门禁全绿 |
| C 类守卫 | `node deploy/tools/check_c_guard.mjs` PASS（C-10 两项豁免仍唯一） |

## 5. 回滚

- 每阶段独立提交，可单独 revert；4A/4B/4C 均为纯 Java 内部分层，无 DDL、无配置、无前端契约变化。
- 4D 独立提交；若危机链回归异常，直接 revert 该提交即可回到「无事务边界」的现状。

## 6. 批准状态

- [x] 4A / 4B / 4C：用户「好的，执行吧」批准（2026-11-12）。
- [x] 4D（B-13）：用户「批准 Task 4，继续按照 spec 和 plan 推进项目」专项批准（2026-11-12）。

## 7. 变更记录（4A / 4B / 4C 落地）

### 7.1 SSE 事件序列对照

拆分前后逐字一致（`TaskEventPublisher` 为唯一出口）：

| 路径 | 事件序列 |
|---|---|
| 正常成功 | `task_created` → `task_status(RUNNING)` → 每步 `step_started` → `middle_result` → `task_status(SUCCESS)` → `done(status, payload)` |
| 步骤重试 | 单步内 `step_retry(stepSeq, agent, attempt)` 最多 2 次，之后照常 `middle_result` |
| 输出校验降级 | `step_started` → `step_failed(stepSeq, agent, reason)` → `task_status(PARTIAL_SUCCESS)` → `done(status, payload)`（payload 可为 `null`） |
| 取消 | `task_created` → `task_status(CANCELLED)` → `done(status)`（无 `payload` 键） |
| 执行异常 | `task_status(FAILED)` → `error(message="任务执行失败")` |

载荷键未增删改：`taskNo/status`、`stepSeq/agent`、`attempt`、`payload`、`reason`、`message`。

### 7.2 状态机迁移表

`TaskInstanceEntity.status` 仍为 `String`，DB 值与既有数据完全兼容；状态机只在写库前判定，非法迁移抛 `IllegalStateException`（内部 bug，不映射错误码）。

| 起点 | 允许目标 | 触发点 |
|---|---|---|
| PENDING | RUNNING、CANCELLED | 执行开始 / 用户取消 |
| RUNNING | SUCCESS、PARTIAL_SUCCESS、FAILED、CANCELLED | 正常收口 / 降级 / 异常 / 取消 |
| FAILED | RUNNING | A1 手动重跑 |
| PARTIAL_SUCCESS | CANCELLED | 兼容既有取消语义 |
| CANCELLED | CANCELLED | 取消幂等 |
| SUCCESS | —（终态不可逆） | — |

### 7.3 提交钩子时序变更（有意改良）

| 阶段 | 旧时序 | 新时序 |
|---|---|---|
| 日记提交 | `TaskController` 先 `submit`（虚拟线程已派发）→ 再同步 `diaryService.attachTask` | `TaskSubmissionService`：落库 → `task_created` → hook（`attachTask`，`@Transactional` 提交）→ 派发 |
| 收益 | 流水线可能先于日记行启动，读库存在竞态 | 流水线启动时日记行必已存在 |
| 幂等 | `clientReqId` 重放返回旧任务，但 Controller 仍会再调 `attachTask` | 重放提前返回，hook 与派发都不执行 |

### 7.4 `SubmissionOrigin` 设计说明

- `EXTERNAL`：HTTP 提交与各领域正向提交，执行匹配的 `TaskSubmissionHook`。
- `INTERNAL`：`DiaryService.reanalyze` 等复用既有业务记录的内部重分析，跳过 hook，避免同一篇日记生成第二行。
- 依赖注入用 `ObjectProvider<TaskSubmissionHook>` 延迟解析，消除 `SubmissionService → Hook → DiaryService → OrchestratorService → SubmissionService` 构造环。

### 7.5 提交期边界校验（新增早失败）

未知流水线（3002）与缺失必需入参（2001：`SIMULATE_PIPELINE.simulateId`、`COMPANION_PIPELINE.sessionId`、`GROWTH_LETTER_PIPELINE.statWeek/material.dataPoints`）由「异步步骤内失败」前移为「提交即 400」，不再产生任务行；`diaryText` 空串仍放行，保持既有静默跳过语义。

## 8. 变更记录（4D / B-13 落地）

### 8.1 事务边界

| 单元 | 实现 | 语义 |
|---|---|---|
| 风险事件 + 审计 + 指标 | `RiskArchiveService.record` `@Transactional` | 事件落库与 `sv.risk.event` 计数同事务；审计 `AuditService` 保持自身 `REQUIRES_NEW`（C-05 零改动） |
| 周画像 | `RiskArchiveService.updateWeeklyProfile` 经 `TransactionTemplate`（`PROPAGATION_REQUIRES_NEW`） | 独立事务，异常在服务内捕获 → 返回 `false`，回执记 `profileUpdated=false`，不阻断归档 |
| 危机迁移 | `CrisisService.onHighRisk`（C 类，未改动） | 在 `record` 事务提交后调用，入参 `riskEventId` 与既有语义一致 |

### 8.2 职责与顺序

- `RiskArchiveAgent` 只保留：规则轨 → 语义轨 → 最高级别合并 → 回执组装；持久化全部委托 `RiskArchiveService`。
- 调用顺序保持：规则轨 → 语义轨 → 风险事件/审计 → 危机迁移 → 周画像 → 回执。
- 无 DDL、无唯一键，沿用「每任务每触发一条事件」的幂等现状。

### 8.3 4D 门禁

| 门禁 | 结果 |
|---|---|
| `mvn -q test` | 51 套件 / 416 例，0 失败 0 错误（9 跳过） |
| 风险矩阵 | `M9DualTrackMatrixTest` 5 例全绿 |
| golden 风险场景 | 15 个场景字段路径一致；场景 6 `riskLevel=HIGH`、`referral.show=true`、`profileUpdated=true`，危机日志 `entered CRISIS` |
| C 类守卫 | PASS（175 个变更路径，仅既有 C-10 两项豁免） |
