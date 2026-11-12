# Batch 5 Checklist

> 依据：`spec.md` §2/§3。红线：C 类目录零改动、单节点行为不变、错误码只增不改。

## A. B-10 Flyway

- [x] A1 `flyway-core` / `flyway-mysql` 依赖就位，版本由 Boot BOM 管理
- [x] A2 `V1__baseline.sql` 与 `deploy/sql/schema.sql` 除库/账号语句外逐字一致（库由部署脚本预建）
- [x] A3 既有 MySQL 库启动后 `flyway_schema_history` 含 baseline 记录且业务表未被重建
- [x] A4 空库由 V1 建库、V2 增量（V1 为 schema.sql 去除库/账号语句后的首建脚本）
- [x] A5 测试 profile 关闭 Flyway，425 例回归全绿；二次启动 `Schema is up to date`

## B. B-09 乐观锁

- [x] B1 `V2__optimistic_lock.sql` 为 4 张表新增 `version`，不改既有列语义
- [x] B2 4 个实体标注 `@Version`；JPA 更新语句携带版本判定
- [x] B3 冲突映射为 HTTP 409 / code 2003，串行写入无行为变化
- [x] B4 前端冲突路径有明确提示并重取，不静默覆盖
- [x] B5 并发测试证明同一实体双写必有一方冲突（`OptimisticLockTest`）

## C. B-07/16 认证

- [x] C1 登录失败计数按 `username+IP`，阈值/窗口/错误码与现状一致
- [x] C2 攻击者无法通过单一用户名锁死他人账号（同名换 IP 可正常登录）
- [x] C3 Redis 读故障：鉴权降级放行且有 `sv.redis.degraded` 指标
- [x] C4 Redis 写故障：refresh/logout/吊销返回 503 / 5002，不产生半吊销状态
- [x] C5 前端 401 单飞刷新 + 跨标签互斥；SSE 连接复用同一刷新并重试一次
- [x] C6 并发刷新测试：多标签同时 401 只触发一次 refresh，不误触重用检测

## D. B-17 上传/导出

- [x] D1 音频仅接受白名单 MIME + 合法魔数，扩展名不参与信任
- [x] D2 文件名固定由服务端生成（内存态，不进入存储路径）
- [x] D3 导出下载 token 二次使用被拒（`ArchiveExportTest`）
- [x] D4 下载动作写审计（`EXPORT_DOWNLOAD`）

## E. B-18 ArchUnit

- [x] E1 ArchUnit 测试纳入 `mvn test`
- [x] E2 Controller→Repository 规则生效（存量冻结、新违规拦截）
- [x] E3 api→domain 与字段注入规则生效

## F. B-20 小程序单测

- [x] F1 Vitest 可在 `miniprogram/` 独立运行（`npm run test`）
- [x] F2 覆盖 `api/http`、`stores/auth`、`utils/date`、`utils/emotions`（20 例）
- [x] F3 `typecheck` / `lint` / `build:mp-weixin` 全绿

## G. Stage 5B（2026-11-12 书面批准）

- [x] G1 `AuditChainLockAspect` 包外实现，`audit/**` 零改动，哈希算法与字段不变
- [x] G2 审计锁争用/Redis 故障行为符合设计（争用 5002、故障降级告警，不静默丢失）
- [x] G3 `ScheduledLockAspect` 覆盖全部 `@Scheduled`，单节点行为不变
- [x] G4 两实例并发调度，每个任务每次触发只执行一次（Redis 集成测试）
- [x] G5 `MasterKeyStartupGuard` 在 prod profile 缺密钥时启动失败，dev 保留临时密钥告警
- [x] G6 开关 `soul.lock.enabled=false` 可回到单节点语义

## H. 收口

- [x] H1 后端 437 例全绿；前端 112 例与 build；小程序 20 例 test/typecheck/lint
- [x] H2 OpenAPI 与两端生成类型零 diff
- [x] H3 golden 15 场景字段路径一致
- [x] H4 `check_c_guard.mjs` PASS 且未新增豁免（206 个变更路径）
