# Batch 5 Tasks

> 依据：`.trae/specs/batch5-security-infrastructure/spec.md`
> 节奏：Stage 5A 完成后停靠汇报；Stage 5B 需书面确认。

- [x] Task 1: 5A-1 — B-10 Flyway 迁移基线
  - [x] 1.1 `pom.xml` 加 `flyway-core` + `flyway-mysql`
  - [x] 1.2 `db/migration/V1__baseline.sql`（= `deploy/sql/schema.sql` 快照）；`application.yml` 配置 baseline-on-migrate
  - [x] 1.3 `application-test.yml` 关闭 Flyway；本地 MySQL 演练既有库基线化与新库首建
  - [x] 1.4 门禁：全量单测、后端启动、`flyway_schema_history` 核验

- [x] Task 2: 5A-2 — B-09 乐观锁
  - [x] 2.1 `V2__optimistic_lock.sql`：4 张表加 `version`
  - [x] 2.2 4 个实体加 `@Version`；新增 `CONFLICT(2003)` 与 409 映射
  - [x] 2.3 前端写路径冲突提示 + 重取（打卡/计划/报告反馈/偏好）
  - [x] 2.4 新增并发冲突测试（同实体双写必有一方 409）
  - [x] 2.5 门禁：全量单测、golden、前端测试

- [x] Task 3: 5A-3 — B-07/16 认证加固
  - [x] 3.1 失败计数 key 改 `username+IP`，阈值/窗口/错误码不变
  - [x] 3.2 `RedisGuard` 降级策略：读放行 + 指标；refresh/logout/吊销 503
  - [x] 3.3 前端 `authFetch`：单飞刷新 + `navigator.locks` 跨标签；SSE 复用并 401 重试一次
  - [x] 3.4 新增后端降级测试与前端刷新并发测试
  - [x] 3.5 门禁：全量单测、前端测试、golden

- [x] Task 4: 5A-4 — B-17 上传/导出安全
  - [x] 4.1 音频 MIME 白名单 + 魔数校验 + 文件名清洗
  - [x] 4.2 导出下载 token 单次消费测试 + 下载审计
  - [x] 4.3 门禁：语音/导出相关测试、golden 11/12

- [x] Task 5: 5A-5 — B-18 ArchUnit
  - [x] 5.1 加 `archunit-junit5`；Controller→Repository 规则（存量冻结）
  - [x] 5.2 api→domain 规则 + 字段注入规则
  - [x] 5.3 门禁：全量单测

- [x] Task 6: 5A-6 — B-20 小程序单测
  - [x] 6.1 Vitest 配置 + `test` 脚本
  - [x] 6.2 覆盖 `api/http`、`stores/auth`、`utils/date`、`utils/emotions`
  - [x] 6.3 门禁：小程序 `test`/`typecheck`/`lint`

- [x] Task 7: Stage 5B — C 邻近（**2026-11-12 书面确认**）
  - [x] 7.1 `AuditChainLockAspect`：Redis SET NX PX + 降级
  - [x] 7.2 `ScheduledLockAspect`：统一拦截 `@Scheduled`
  - [x] 7.3 `MasterKeyStartupGuard`：prod profile 强校验
  - [x] 7.4 多节点/故障注入/启动守卫演练

- [x] Task 8: Batch 5 收口
  - [x] 8.1 全门禁：后端/前端/小程序/golden/C guard
  - [x] 8.2 更新基线 tasks.md Task 10 与 checklist.md G8
  - [x] 8.3 变更记录（迁移、锁、降级策略）

# Task Dependencies

- Task 2 依赖 Task 1（version 列以 Flyway 迁移落库）。
- Task 3/4/5/6 相互独立，可在 Task 1 后并行。
- Task 7 依赖用户书面确认；与 5A 不共享文件。
- Task 8 依赖 Task 1–7 完成。
