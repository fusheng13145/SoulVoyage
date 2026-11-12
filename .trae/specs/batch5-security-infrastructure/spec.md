# Batch 5 实施 Spec：安全与基础设施（B-05/06/07/09/10/16/17/18/20）

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §3.3、§3.6 Batch 5；任务来源 `tasks.md` Task 10。
> 性质：B 类。**C 类目录零改动**；5B 三项虽为 C 邻近，但全部采用包外包装实现（见 §3.5），实施前仍需用户书面确认。
> 行为等价红线：单节点行为不变；既有错误码语义不变（只增不改）；危机/审计/加密语义不变。

## 1. 已核实现状（2026-11-12）

| 项 | 现状 |
|---|---|
| B-10 迁移 | 无 Flyway 依赖；`deploy/sql/schema.sql`（30 KB）+ 8 个手工 `migrate_mN.sql`；测试库 H2 `ddl-auto: update` |
| B-09 乐观锁 | 全后端 0 个 `@Version`；`mood_check_in`/`plan_item`/`report`/`user_preferences` 均可多端并发更新 |
| B-07 登录锁 | `AuthService.authenticate` 失败计数 key 仅 `jwt:login-fail:{username}`，可被攻击者锁死任意账号 |
| B-16 Redis 降级 | `JwtService.verify/parse` 直接调用 Redis，故障时全部鉴权 500，无降级策略 |
| SSE 刷新 | `frontend/src/api/sse.ts` 两处裸 `fetch`，401 不刷新、不重试；`http.ts` 单飞刷新仅单标签有效 |
| B-17 上传/导出 | 语音仅校验大小；导出下载 token 单次消费缺少契约测试 |
| B-18 结构 | 24 处 Controller 直接注入 Repository；无 ArchUnit |
| B-20 小程序 | 无 Vitest、无单测脚本；仅 `typecheck`/`lint` |
| B-05 审计链 | `AuditService` 仅 JVM `synchronized`，多节点会读到同一链尾 |
| B-06 定时任务 | 5 个 `@Scheduled` 无分布式锁：`DailySweeper`、`ReminderScheduler`、`CompanionSweeper`、`GrowthLetterService`、`ContentStore` |
| B-08 主密钥 | 未配置 `SV_MASTER_KEY` 时无条件生成临时密钥 |

## 2. 分阶段

### Stage 5A — 非 C 目录（先做）

| 顺序 | 项 | 改动 |
|---|---|---|
| 5A-1 | B-10 Flyway | 加 `flyway-core` + `flyway-mysql`；`db/migration/V1__baseline.sql` = `deploy/sql/schema.sql` 原样快照；`baseline-on-migrate=true`（既有库基线化、新库执行 V1）；后续变更只增 `V2__*.sql`；测试 profile 关闭 Flyway（H2 仍用 ddl-auto），另以本地 MySQL 演练「既有库基线化 + V2 应用」 |
| 5A-2 | B-09 乐观锁 | `V2__optimistic_lock.sql` 为 `mood_check_in`/`plan_item`/`report`/`user_preferences` 加 `version BIGINT NOT NULL DEFAULT 0`；4 个实体加 `@Version`；`GlobalExceptionHandler` 把 `ObjectOptimisticLockingFailureException` 映射为新增 `CONFLICT(2003)` → HTTP 409；前端写路径冲突提示 + 重取 |
| 5A-3 | B-07/16 认证 | 失败计数 key 改 `jwt:login-fail:{username}:{ip}`（阈值/窗口/错误码不变）；新增 `RedisGuard`：读操作降级放行 + `sv.redis.degraded` 指标，写操作（refresh 轮换/吊销/登出）返回 503 `DEPENDENCY_UNAVAILABLE`；前端 `authFetch` 统一 401 单飞刷新（`navigator.locks` 跨标签），SSE 复用并重试一次 |
| 5A-4 | B-17 上传/导出 | 音频 MIME 白名单 + 魔数校验（webm/ogg/m4a/mp3/wav）+ 文件名清洗；导出下载 token 单次消费契约测试；下载审计留痕 |
| 5A-5 | B-18 ArchUnit | 加 `archunit-junit5`（test）；规则：Controller 不得依赖 Repository（存量用 `FreezingArchRule` 冻结）、`..api..` 不得依赖 `..domain..entity/repository`、无 Spring 字段注入；新代码严格拦截 |
| 5A-6 | B-20 小程序单测 | 加 Vitest（devDependency）+ `test` 脚本；覆盖 `api/http`、`stores/auth`、`utils/date`、`utils/emotions`；不引 E2E |

### Stage 5B — C 邻近（包外包装，实施前需书面确认）

| 项 | 设计（C 目录零改动） |
|---|---|
| B-05 审计链跨节点互斥 | 新增 `common/lock/AuditChainLockAspect` 环绕 `AuditService.record`：Redis `SET NX PX`（key `lock:audit:chain`，租约 5s，自旋 2s）；拿不到锁 → 抛 `DEPENDENCY_UNAVAILABLE`（审计不可静默丢失）；Redis 故障 → 退回 JVM 锁 + `sv.audit.lock.degraded` 告警。哈希算法/字段零改动（C-05 不改） |
| B-06 定时任务分布式锁 | 新增 `common/lock/ScheduledLockAspect` 拦截所有 `@Scheduled` 方法：key `lock:sched:{Class}.{method}`，`SET NX PX` 租约 30min，`finally` 释放；未抢到直接跳过（单节点行为不变）。不在 `domain/crisis/**` 内加任何代码（C-03 不改），5 个调度器统一覆盖 |
| B-08 主密钥生产守卫 | 新增 `config/MasterKeyStartupGuard`（`crypto/**` 零改动）：激活 profile 含 `prod` 且 `soulvoyage.security.master-key` 为空 → 启动失败并给出明确错误；dev/test/default 保留临时密钥告警 |

## 3. 验收标准

| 门禁 | 通过条件 |
|---|---|
| 后端单测 | `mvn -q test` 全绿（现有 416 例 + 新增用例） |
| 迁移演练 | 既有 MySQL 库基线化成功（`flyway_schema_history` 出现 baseline+V2）；新库执行 V1+V2 成功；应用启动正常 |
| 并发语义 | 乐观锁冲突返回 409/2003；串行写入行为不变；前端冲突提示可重试 |
| 故障注入 | Redis 停止时：登录可用、refresh/logout 返回 503；鉴权读降级有指标；审计降级有告警 |
| 多节点 | 两个后端实例同时跑定时任务，每个任务单次触发只执行一遍（日志/计数验证） |
| 契约 | OpenAPI 零 diff（新增 2003 不进契约形状）；前端/小程序 `gen:api` 零 diff |
| 前端/小程序 | 前端 108+ 例、小程序 Vitest 新增用例、`typecheck`/`lint`/`build` 全绿 |
| golden | 15 场景字段路径一致 |
| C 类守卫 | `check_c_guard.mjs` PASS（不新增 C 文件改动，也不新增豁免） |

## 4. 回滚

- 每项独立提交。Flyway 可通过移除依赖与 `db/migration` 回滚（已基线化的库保留 history 表不影响运行）。
- `@Version` 列只增不删；回滚代码时列保留（JPA 不映射即忽略）。
- 5B 三个切面均可单独关闭（配置开关 `soulvoyage.lock.enabled=false`），关闭后回到 JVM 单节点语义。

## 5. 批准状态

- [x] 用户「批准 Task 5，开始 Batch 5」（2026-11-12）：批准 Stage 5A 实施。
- [x] Stage 5B（B-05/B-06/B-08）：用户「批准 5B 全部（推荐）」（2026-11-12）书面确认，全部采用包外切面/守卫实现。

## 6. 变更记录

### 6.1 Stage 5A 落地

| 项 | 实现 |
|---|---|
| B-10 Flyway | `flyway-core` + `flyway-mysql`；`db/migration/V1__baseline.sql`（= schema.sql 去除 `CREATE DATABASE/CREATE USER/USE`，库与账号由部署脚本预建，其余逐字一致）+ `V2__optimistic_lock.sql`；`baseline-on-migrate=true`（既有库基线化到 1）；测试 profile 关闭 Flyway |
| B-09 乐观锁 | 4 个实体 `@Version`；新增 `CONFLICT(2003)` → HTTP 409；前端 `isConflict` 冲突后重取（打卡/偏好/报告反馈/收藏） |
| B-07/16 认证 | 失败计数 key 改 `jwt:login-fail:{username}:{ip}`；`RedisGuard` 读降级 + `sv.redis.degraded` 指标，refresh/logout/吊销写失败返回 503/5002；前端 `authFetch` 单飞刷新 + `navigator.locks` 跨标签互斥，SSE 复用并 401 重试一次 |
| B-17 上传 | 语音上传增加容器魔数校验（Content-Type 仅作白名单，不再作为信任依据）；导出一次性/属主断言沿用既有 `ExportStore.take` 与 `EXPORT_DOWNLOAD` 审计 |
| B-18 ArchUnit | `archunit-junit5`；Controller→Repository、api→domain 存量冻结（`archunit_store/`），字段注入严格禁止 |
| B-20 小程序 | Vitest（node 环境）+ `npm run test`；覆盖 `api/http`、`stores/auth`、`utils/date`、`utils/emotions` |

### 6.2 Stage 5A 门禁

| 门禁 | 结果 |
|---|---|
| 后端 `mvn -q test` | 54 套件 / 425 例，0 失败 0 错误（9 跳过） |
| Flyway 演练 | 既有 MySQL 库：baseline V1 + 应用 V2；二次启动 `Schema is up to date`；4 张表 `version bigint` 就位 |
| 前端 | 112 例全绿；`npm run build` 通过 |
| 小程序 | Vitest 20 例；`typecheck` / `lint` / `build:mp-weixin` 通过 |
| 契约 | 两端 `gen:api` 零 diff；`OpenApiContractTest` 通过 |
| golden | 15 场景字段路径一致（14-admin-task/retry 仍 SKIPPED） |
| C 类守卫 | PASS（202 个变更路径，仅既有 C-10 两项豁免；C 目录零改动） |

### 6.3 Stage 5B 落地（2026-11-12 书面批准后）

| 项 | 实现 |
|---|---|
| B-05 审计链跨节点互斥 | `common/lock/AuditChainLockAspect` 环绕 `AuditService.record`：Redis `SET NX PX`（租约 5s，自旋 2s）；争用失败 → 5002；Redis 故障 → 进程内 JVM 锁 + `sv.lock.degraded` 告警。`audit/**` 零改动，哈希算法/字段不变 |
| B-06 调度分布式锁 | `common/lock/ScheduledLockAspect` 拦截全部 `@Scheduled`（5 个调度器 7 个方法）：key `lock:sched:{Class}.{method}`，租约 30min；未抢到 → `sv.sched.skipped` 计数并跳过；`domain/crisis/**` 零改动 |
| B-08 主密钥守卫 | `config/MasterKeyStartupGuard`：prod profile 缺 `SV_MASTER_KEY` → 启动失败；dev/test/default 保持临时密钥告警。`crypto/**` 零改动 |
| 开关 | `soulvoyage.lock.enabled=false` 时锁切面直接放行，回到单节点语义 |

**Stage 5B 门禁**

| 门禁 | 结果 |
|---|---|
| 后端 `mvn -q test` | 59 套件 / 437 例，0 失败 0 错误（9 跳过） |
| 多节点语义 | `DistributedLockRedisTest`：两实例共享 key，后到者跳过、释放后可获取 |
| 故障注入 | `DistributedLockTest`：Redis 故障降级为进程内语义并计数；关闭开关不触达 Redis |
| 审计争用 | `AuditChainLockAspectTest`：拿不到锁明确 5002，不静默丢审计 |
| 启动守卫 | prod + 空密钥 → `APPLICATION FAILED TO START`（IllegalStateException 明确提示）；dev 正常启动 |
| golden | 15 场景字段路径一致 |
| C 类守卫 | PASS（206 个变更路径，仅既有 C-10 两项豁免；C 目录零改动） |
