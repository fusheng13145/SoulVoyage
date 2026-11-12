# Batch 4 Checklist

> 依据：`spec.md` §3/§4。行为等价红线：流水线步骤顺序、SSE 事件名/顺序/载荷键、任务状态值、错误码语义不变。

## A. Stage 4A — B-04 枚举与边界校验

- [x] A1 `PipelineCode` 覆盖 5 条流水线；`from` 对空/未知值抛 `BizException(PIPELINE_NOT_ALLOWED=3002)`
- [x] A2 `TaskDefinition.code()` 返回 `PipelineCode`，5 个 Pipeline 实现均返回枚举常量
- [x] A3 `StepSpec.agentCode` 与 `Agent.code()` 均为 `AgentCode`，7 个 Agent 实现切换
- [x] A4 `AgentRegistry`/`PipelineRegistry` 以 `EnumMap` 索引，启动期校验枚举全覆盖、steps 非空、Agent 已注册
- [x] A5 5 条流水线 `validatePayload` 按 spec 表格生效（缺 `simulateId`/`sessionId`/`statWeek`/`material.dataPoints` → 2001）
- [x] A6 `PipelineContractTest` 断言枚举一一对应、未知流水线 400/3002、缺参 400/2001 且不产生任务行
- [x] A7 `M9DualTrackMatrixTest` 的 `StepSpec` 已强类型化并全绿

## B. Stage 4B — B-03 编排拆分与显式状态机

- [x] B1 `TaskStatus` 值与原 DB 字符串完全一致（PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED）
- [x] B2 `TaskStateMachine` 迁移表与 spec 一致；非法迁移抛 `IllegalStateException`；`TaskInstanceEntity.status` 仍为 `String`
- [x] B3 `TaskEventPublisher` 集中 8 个事件名与载荷键，与拆分前逐字一致
- [x] B4 `TaskSubmissionService` 承担幂等、单用户 2 活跃上限、落库、`task_created`、派发、重跑起点
- [x] B5 `TaskExecutionService` + `TaskStepRunner` 保留 `MAX_ATTEMPTS=3`、`BACKOFF_MS={2000,8000}`、`abbreviate(500)`、指标与 step 日志
- [x] B6 `OrchestratorService` 收敛为门面且公开签名不变；`nextConsumer` 已删除
- [x] B7 `OrchestratorEventSequenceTest` 断言 SUCCESS / PARTIAL_SUCCESS / CANCELLED 三条序列

## C. Stage 4C — B-11 Controller 与 DiaryService 解耦

- [x] C1 新增 `TaskSubmissionHook` 与 `DiarySubmissionHook`（仅 `DIARY_PIPELINE`）
- [x] C2 hook 在落库后、派发前执行；`SubmissionOrigin.INTERNAL` 跳过 hook
- [x] C3 `TaskController` 不再依赖 `DiaryService`，无 `"DIARY_PIPELINE"` 分支
- [x] C4 `DiaryService.reanalyze` 走 `submitInternal`，不重复生成日记行
- [x] C5 `DiaryTaskSubmissionTest` 覆盖提交后可见、幂等重放单行单任务

## D. 门禁

- [x] D1 `mvn -q test` 全绿
- [x] D2 `OpenApiContractTest` 通过，`openapi.json` 零 diff
- [x] D3 golden 15 场景字段路径一致（`snapshot_batch0.mjs` + 路径比对）
- [x] D4 前端 `npm run test`（108 例）与 `npm run build` 通过
- [x] D5 小程序 `npm run typecheck` 与 `npm run lint` 通过
- [x] D6 `check_c_guard.mjs` PASS，C 类零改动（既有 C-10 两项豁免除外）
- [x] D7 前端/小程序 `npm run gen:api` 生成类型零 diff
- [x] D8 Stage 4D 严格遵循"先专项批准、后实施"（批准记录见 spec §6，验收见 E 节）

## E. Stage 4D — B-13 风险归档事务边界（专项批准后）

- [x] E1 `RiskArchiveService.record` 以 `@Transactional` 承载风险事件 + 审计 + `sv.risk.event` 计数
- [x] E2 周画像 `updateWeeklyProfile` 独立事务（`REQUIRES_NEW`），异常在服务内吞掉并返回 `false`
- [x] E3 `RiskArchiveAgent` 仅保留判定与回执；顺序仍为 规则轨 → 语义轨 → 归档 → 危机迁移 → 周画像
- [x] E4 `CrisisService` 未改动，`onHighRisk(userId, riskEventId)` 调用位置与入参不变
- [x] E5 门禁：416 例全绿、`M9DualTrackMatrixTest` 5 例、golden 15 场景一致（风险场景 6 HIGH）、C guard PASS
