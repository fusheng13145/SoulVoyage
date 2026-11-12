# Batch 4 Tasks

> 依据：`.trae/specs/batch4-core-orchestration/spec.md`
> 节奏：阶段门禁后停靠汇报；4D 需专项批准。

- [x] Task 1: Stage 4A — B-04 枚举与 payload 边界校验
  - [x] 1.1 新增 `PipelineCode`（`from(String)` 抛 3002），`TaskDefinition.code()` 改枚举，5 个 Pipeline 实现切换
  - [x] 1.2 `StepSpec.agentCode` 改 `AgentCode`，`Agent.code()` 改枚举，7 个 Agent 实现切换
  - [x] 1.3 `AgentRegistry` / `PipelineRegistry` 强类型化 + `@PostConstruct` 注册完整性校验
  - [x] 1.4 `TaskDefinition.validatePayload` 默认方法 + 5 条流水线结构校验；`submit` 首行解析与校验
  - [x] 1.5 新增 `PipelineContractTest`；修 `M9DualTrackMatrixTest` 的 `StepSpec` 构造
  - [x] 1.6 门禁：`mvn -q clean test`、OpenAPI 零 diff、golden 15 场景、C guard

- [x] Task 2: Stage 4B — B-03 编排拆分与显式状态机
  - [x] 2.1 新增 `TaskStatus` + `TaskStateMachine`（值不变、终态不可逆）
  - [x] 2.2 新增 `TaskEventPublisher`，集中 8 个事件名与载荷键
  - [x] 2.3 抽取 `TaskSubmissionService`（幂等/并发/落库/task_created/派发/重跑起点）
  - [x] 2.4 抽取 `TaskExecutionService` + `TaskStepRunner`（循环/降级/重试/日志/指标/续接）
  - [x] 2.5 `OrchestratorService` 收敛为门面并删除 `nextConsumer`
  - [x] 2.6 新增 `OrchestratorEventSequenceTest`（SUCCESS / PARTIAL_SUCCESS / CANCELLED 三条序列）
  - [x] 2.7 门禁：全量单测、SSE 测试、golden 5-diary-task 与 7-simulation、C guard

- [x] Task 3: Stage 4C — B-11 Controller 与 DiaryService 解耦
  - [x] 3.1 新增 `TaskSubmissionHook` + `DiarySubmissionHook`
  - [x] 3.2 `TaskSubmissionService` 在落库后、派发前调用匹配 hook
  - [x] 3.3 `TaskController` 删除 `DiaryService` 依赖与 DIARY 分支
  - [x] 3.4 新增 `DiaryTaskSubmissionTest`（落库可见 + 幂等重放不重复）
  - [x] 3.5 门禁：`DiaryBookTest`、`M11VoiceDiaryTest`、golden 5-diary-task、C guard

- [x] Task 4: Stage 4D — B-13 风险归档事务边界（**2026-11-12 专项批准**）
  - [x] 4.1 新增 `RiskArchiveService`：风险事件 + 审计原子写入
  - [x] 4.2 周画像独立事务、失败不阻断（`profileUpdated=false` 语义保留）
  - [x] 4.3 `RiskArchiveAgent` 仅做判定与回执，持久化下沉；危机迁移调用点不变
  - [x] 4.4 门禁：`M9DualTrackMatrixTest`、风险 golden 6/15、C guard、危机链手工回归

- [x] Task 5: Batch 4 收口
  - [x] 5.1 全门禁：后端 / 前端 / 小程序 / golden 15 场景 / C guard
  - [x] 5.2 更新 `.trae/specs/establish-soulvoyage-baseline/tasks.md` Task 9 与 `checklist.md` G7
  - [x] 5.3 输出变更记录（事件序列对照、状态机迁移表、hook 时序变更说明）
