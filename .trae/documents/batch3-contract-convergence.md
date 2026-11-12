# Batch 3 实施计划：B 类契约收敛（B-01 / B-02 / B-15 / B-19）

> 依据：`.trae/specs/establish-soulvoyage-baseline/spec.md` §3.3、§3.6 Batch 3；`tasks.md` Task 8
> 性质：B 类谨慎重构，**逐项书面批准后才执行**；本文件仅为计划，未改动任何代码。
> 前置：Batch 0/1/2 已完成；基线 golden 在 `docs/baseline/golden/`；契约快照在 `docs/baseline/openapi.json`。

## 1. 原则（不可突破）

1. 字段名保持不变；状态码、错误码、业务字段不得漂移（Spec §3.6 行为等价验收）。
2. 先读接口、后写接口；每个域单独提交、单独 golden diff。
3. 不改状态机、风险判定、加密落库、审计算法、表结构语义。
4. 任何 C 类文件零改动（`check_c_guard.mjs` 必须 PASS）。
5. 每步必须可回滚：DTO 增加适配期，不改调用方语义。

## 2. 现状证据

| 项 | 现状 |
|---|---|
| B-01 | 52 个 Controller 方法返回 `ApiResponse<Map<String,Object>>`；`openapi.json` 中对应 schema 为宽泛对象；前端手写类型与生成类型并存 |
| B-02 | `DiaryService` 列表先解密全部日记再内存过滤/分页（O(n) 解密 + 全量加载） |
| B-15 | 前端仅 `api/http.ts`、`api/sse.ts`、`api/admin.ts`；业务视图直接拼路径；`schema.d.ts` 仅 admin 使用 |
| B-19 | `ErrorCode` 无 5000 段；`GlobalExceptionHandler` 未知异常映射 `BAD_PARAMS(2001)`；缺少依赖不可用/限流分类 |

## 3. 执行阶段（每阶段单独验收）

### 3A — B-15 前端领域 API 模块（纯前端结构，最低风险）

| 文件 | 改动 |
|---|---|
| 新增 `frontend/src/api/domains/{diary,emotion,checkin,plan,report,archive,letter,achievement,notification,reading,simulation,companion,exercise,account,auth,task}.ts` | 每域导出类型 + 方法；内部复用 `http`/`sse`；类型优先来自 `schema.d.ts`，缺口用 `types/domain.ts` |
| 修改 `views/*.vue` | 只替换 `http.get/post(...)` 为领域方法；不改请求参数/响应处理/交互 |
| `api/admin.ts` | 保留，纳入 domains/admin 命名空间或继续独立 |

- 风险：全部页面请求层；中风险但纯前端。
- 验收：lint/format/typecheck/test/build；网络面板路径与参数不变；无 4xx/5xx 新增。

### 3B — B-01 只读接口 DTO 化（约 20 个方法）

范围（先读）：`AuthController.me`、`EmotionController.trajectory/profile`、`PlanController.active/list/detail`、`ReportController.list/detail`、`AchievementController.wall`、`ReadingController.today`、`DiaryController.list/detail/getDraft`、`NotificationController.list/getPrefs`、`LetterController.list`、`MoodCheckInController.month`、`CompanionController.history`、`SimulationController.list`、`AdminTaskController.detail`、`AdminBoardController.detail/stats`、`AdminMetricsController.overview`、`AdminOpsController.verifyAudit`、`ContentAdminController.kgGraphAudit/preview`。

| 文件 | 改动 |
|---|---|
| 各域 `dto/XxxResp.java` | 新增 record；字段名/类型与现有 JSON 完全一致；嵌套结构同样 DTO 化 |
| 各 Controller | 返回类型改为 `ApiResponse<XxxResp>`；内部构造逻辑保持 |
| `backend/openapi/openapi.json` | 重新生成并入库 |
| `frontend/src/api/schema.d.ts`、`miniprogram/src/api/schema.d.ts` | `gen:api` 重新生成 |

- 风险：接口契约层；字段不变，但序列化顺序/空值策略可能变化，必须 golden diff。
- 验收：`mvn test` 全绿；15 个 golden 场景 diff 仅允许“结构等价”；OpenAPI diff 审阅通过。

### 3C — B-01 写接口 DTO 化（约 30 个方法）

范围（后写）：`DiaryController.update`、`ReadingController.toggleFavorite`、`ReportController.star/feedback`、`NotificationController.putPrefs`、`AccountController.createDataExport`、`VoiceDiaryController.transcribe`、`SimulationController.open`、`CompanionController.noAnalyze/end`、`ExerciseController.checkIn`、`PlanController`（无写）、`AdminTaskController.retry`、`AdminSupportController.freeze/unfreeze`、`AdminRiskController.reveal/review`、`AdminBoardController.create/addMembers/removeMember`、`ContentAdminController.upsertKg/upsertScene/upsertExercise/refresh/publish`。

- 原则同 3B；按域拆 4 个提交：日记/内容、报告/通知/账户、模拟/漫聊/练习、管理端。
- 验收：同 3B + 管理端 smoke（重跑、复核、发布、群体看板）。

### 3D — B-19 错误码与 HTTP 映射统一（核心契约，需单独批准）

| 文件 | 改动 |
|---|---|
| `common/api/ErrorCode.java` | 只增不改：新增 `INTERNAL_ERROR(5000, "服务内部错误")`、`DEPENDENCY_UNAVAILABLE(5002, "依赖服务暂不可用")`；保留现有全部码值 |
| `common/exception/GlobalExceptionHandler.java` | 未知异常 → HTTP 500 + `INTERNAL_ERROR`；参数校验 → 400 + `2001`；`BizException` 按码段映射；补 `MethodArgumentNotValidException` 统一 body |
| 新增 `ErrorCodeMappingTest` | 契约测试：每个错误码 → HTTP 状态 + body.code 固定 |
| 前端 `http.ts` | 仅按非零处理；如需区分 5000 段做提示映射，不改变现有 401 单飞刷新 |

- 风险：全接口错误契约；前端已按非零处理，影响可控；但 `A-12` 此前未批准，本项包含该修复。
- 验收：错误码契约测试；golden 中错误场景重跑；前端 401/403/500 行为手工验证。

### 3E — B-02 日记列表 SQL 分页与搜索（默认延后）

- 现状：解密全部后内存过滤/分页；日期范围下推 SQL 可行，但 `q` 搜索需密文索引或“分页后解密过滤”，会改变总数语义。
- 方案候选：
  1. **日期下推 + 分页后过滤**：`record_date` 范围与 `deleted=0` 下推；`q` 仅在当前页解密过滤（总数语义变为“页内命中”）。
  2. **密文索引**：新增盲索引列（HMAC），支持等值/前缀搜索；需表结构与迁移，风险高。
  3. **应用层缓存**：短期缓存解密结果，不解决 O(n)。
- 建议：先做 1（不改表），把 `q` 语义明确为“页内搜索”；若要全局搜索，另立产品变更 + 盲索引专项。**需单独批准后再执行。**

## 4. 改动文件汇总

- 后端：各域 `dto/*.java`（新增）、对应 Controller（返回类型）、`ErrorCode.java`、`GlobalExceptionHandler.java`、`openapi/openapi.json`
- 前端：`api/domains/*.ts`（新增）、各 `views/*.vue` 的请求调用、`api/schema.d.ts`
- 小程序：`api/schema.d.ts`（仅重新生成）
- 测试：`ErrorCodeMappingTest`、必要的 DTO 序列化测试
- 不碰：`crypto/**`、`agent/risk/RiskRules`、`domain/crisis/**`、`llm/OutputValidator`、`resources/schemas/**`、`audit/**`、`schema.sql`、`GroupBoardService`、`AccountService`、`WxBindTicketStore`、危机 UI

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| DTO 序列化差异导致前端空白 | 字段名/类型冻结；每域 golden diff；前端 typecheck 强制 |
| OpenAPI 生成类型大面积变化 | 先读后写、逐域提交；`gen:api` diff 逐条审阅 |
| 错误映射改变前端提示 | 保留现有码值；前端仅按非零处理；补契约测试 |
| 管理端写接口行为变化 | 单独提交 + smoke（任务重跑/风险复核/内容发布/看板） |
| B-02 改变搜索语义 | 默认延后；方案 1 需明确“页内搜索”产品口径 |

## 6. 验收门禁

1. `mvn test` 全绿、`RiskRulesTest` 必绿；`mvn verify` 覆盖率不低于基线。
2. `node deploy/tools/snapshot_batch0.mjs` 重跑，15 场景 golden diff 审阅。
3. `npm run gen:api` 后两端 `git diff --exit-code`（DTO 提交后必须重新生成并入库）。
4. 前端 lint/format/typecheck/test/coverage/build；小程序 lint/format/typecheck/build 双端。
5. `node deploy/tools/check_c_guard.mjs` PASS。
6. 每阶段独立提交，可单独 revert；DTO 增加适配期，旧字段不删。

## 7. 待确认（逐项审批）

1. 是否批准 **3A（B-15 前端领域 API）**？
2. 是否批准 **3B（B-01 只读接口 DTO 化）**？
3. 是否批准 **3C（B-01 写接口 DTO 化）**？
4. 是否批准 **3D（B-19 错误码统一）**？
5. **3E（B-02 日记分页/搜索）** 是否本批执行？建议延后到独立变更单。

## 8. Stage 3A 执行结果（B-15，已完成）

### 8.1 新增领域模块（18 个）

`frontend/src/api/domains/`：`auth`、`task`、`diary`、`emotion`、`checkin`、`plan`、`report`、`reading`、`archive`、`letter`、`achievement`、`notification`、`simulation`、`companion`、`exercise`、`account`、`content`、`risk`、`voice`。

- 统一形态：路径与参数集中在领域模块内部，返回 `r.data.data`；列表/详情类响应以泛型 `<T>` 在调用处收窄，待 3B 生成类型后替换。
- 未改任何后端代码、未改 `api/http.ts` 拦截器语义。

### 8.2 已迁移调用方

- Store：`stores/auth.ts`、`stores/content.ts`、`stores/crisis.ts`（类型从领域模块 re-export）。
- Composable：`composables/voiceNote.ts`。
- 视图（16）：`TodayView`、`DiaryWriteView`、`DiaryListView`、`DiaryDetailView`、`PracticeView`、`PracticeRoomView`、`InsightsView`、`SimulationView`、`CompanionView`、`AccountView`、`ArchiveView`、`ReportDetailView`、`LettersView`、`AchievementsView`、`NotificationsView`、`MeView`。

### 8.3 迁移中修正的等价性问题（均为调用形态适配，无行为变化）

- `companionApi.noAnalyze/end`、`simulationApi.interrupt` 形参放宽为 `number | string`（视图侧 id 为 number）。
- `TodayView.toggleFavorite` 按领域方法返回值解构 `{ favorited }`。
- `DiaryDetailView` 路由参数显式 `String(...)` 传入。
- `PracticeRoomView` 补漏的离场记录调用改为 `exerciseApi.record`。
- `notificationApi.putPrefs` 形参改为 `object`，以接受 `Partial<Prefs>`。

### 8.4 保留的直连

- `api/admin.ts`：管理端独立 API 层，按计划保留。
- `api/sse.ts`：SSE 传输层内部的 `GET /tasks/{taskNo}` 轮询兜底，属 API 层而非视图/Store。
- 视图仅剩 `import type { Json }` 的类型引用。

### 8.5 门禁结果

- `npm run lint` PASS；`npm run format:check` PASS（先对 `archive.ts`/`task.ts` 执行 prettier 修复）。
- `npm run typecheck` PASS。
- `npm run test`：11 文件 / 108 用例全通过。
- `npm run build` PASS。
- 未触碰 C 类文件，`check_c_guard` 无需豁免运行。

**Stage 3A 门禁通过，按“分阶段停靠”暂停，等待确认后进入 3B。**

## 9. Stage 3B 执行结果（B-01 只读 DTO，已完成）

### 9.1 范围与方法

- 覆盖 3B 计划中的全部只读端点：`/auth/me`、情绪轨迹/画像、计划 active/list/detail、报告 list/detail、成就墙、每日一读、日记 list/detail/draft、通知 list/prefs、成长来信、打卡月视图、漫聊历史、模拟会话列表、管理端任务详情/审计校验/群体看板 detail+stats/观测 overview/内容对账+预览。
- **服务层零改动**：`PlanService`、`DiaryService`、`GroupBoardService` 等仍返回原 Map（测试与导出链路依赖），由 Controller 在边界映射为 DTO；`GroupBoardService`/`AccountService` 等 C 类文件未触碰。
- 动态正文（报告 `content`、日记 `eventTags`、模拟四维分、KG 对账明细、看板指标）保留 `JsonNode`/`Object` 字段承载，字段名与嵌套层级不变。

### 9.2 新增 DTO

`auth/AuthDtos.MeResp`，以及 `domain/{emotion,plan,report,achievement,content,diary,notify,letter,checkin}.dto`、`agent/{companion,simulate}.dto`、`admin.dto` 共 12 个 DTO 文件。`ReportMeta` 从 Controller 迁入 `ReportDtos`（仅测试 import 随之调整）。

### 9.3 行为等价处理

- 抑制态看板与图未启用对账：对可选字段加 `@JsonInclude(NON_NULL)`，保持“键不存在”而非“键为 null”的既有 JSON 语义。
- `avgValence`、`dimensionScores` 等历史动态字段保持 `Object`，不改变空值与数字的表现。
- 测试更新仅为访问器替换（Map#get → record accessor）；无断言口径变化。

### 9.4 契约产物

- `backend/openapi/openapi.json` 重新生成：+1853 / −100 行；新增 `MeResp`、`TrajectoryResp`、`ReportListResp`、`DiaryDetailResp`、`MetricsOverviewResp`、`BoardStatsResp`、`KgGraphAuditResp` 等强类型 schema，未纳入本阶段的端点仍保持原 schema。
- `frontend/src/api/schema.d.ts`、`miniprogram/src/api/schema.d.ts` 经 `gen:api` 重新生成并入库。

### 9.5 门禁结果

- 后端 `mvn test`：401 例，0 失败，9 跳过（`Neo4jKgContractTest` 无真图实例）。
- Golden：15 场景全部执行并重写 `docs/baseline/golden/`；字段路径清单与改造前 Map 契约逐项一致（含 `plan.plan` 包裹、报告详情、打卡月视图、任务步骤、看板抑制态）。
  - 说明：`docs/baseline/` 当前未被 git 跟踪，无法用 `git diff` 对照旧 golden；本次以字段路径清单 + 后端契约测试作为等价证据。建议后续将 baseline 纳入版本库，恢复可 diff 门禁。
- 前端：lint / format:check / typecheck / test（108 例）/ build 全通过。
- 小程序：lint / format:check / typecheck / build:h5 / build:mp-weixin 全通过。
- `check_c_guard`：PASS（仅 Batch 2 已批准的 C-10 两项以 `--allow` 豁免）。

**Stage 3B 门禁通过，按“分阶段停靠”暂停，等待确认后进入 3C（B-01 写接口 DTO 化）。**

## 10. Stage 3C 执行结果（B-01 写接口 DTO，已完成）

### 10.1 范围

按域覆盖全部仍返回 `Map`/`Map<String,String>` 的写接口：

- 日记/内容：日记编辑、重分析、语音转写、每日一读收藏、轻量打卡。
- 报告/通知/账户：收藏、反馈、偏好更新、档案导出票据、个人数据导出票据。
- 任务/模拟/漫聊/练习：任务提交、模拟开场/中途退出/结束复盘、漫聊「这句别分析」/收段、跟练打卡、认知书写。
- 管理端：任务重跑、用户冻结/解冻、风险证据解密/复核结案、群体创建/加成员/移除成员、内容三类 upsert/强制刷新。

计划中未逐项列出的 `reanalyze`、`interrupt`、`finish`、`cognitive`、`selfRating`、`submit`、`archive/export` 一并收敛，以闭合 B-01 写侧；未包含纯读端点（留给后续批次）。

### 10.2 新增/扩展 DTO

- `TaskDtos`：`SubmitResp`、`TaskNoResp`（跨域复用任务回执）。
- `DiaryDtos`：`DiaryUpdateResp`、`VoiceTranscriptionResp`；`EmotionDtos.SelfRatingResp`；`ReadingDtos.FavoriteToggleResp`；`ReportDtos.StarResp/FeedbackResp`；`NotificationDtos.PrefsPatchReq`；`CompanionDtos.NoAnalyzeResp/EndResp`；`SimulationDtos.OpenSceneResp/InterruptResp`。
- 新增 `ExerciseDtos.CheckInResp`、`ArchiveDtos.ExportTicketResp`。
- `AdminDtos`：`RetryResp`、`UserStatusResp`、`RiskRevealResp`、`RiskReviewResp`、`RemoveMemberResp`；`ContentAdminDtos.ContentWriteResp/ContentVersionResp`。

### 10.3 行为等价处理

- 202 Accepted、`taskNo` 空串、`completed=true`、`difficulty` 缺省 `NORMAL` 等既有语义逐项保留。
- 群体创建/加成员复用 `BoardDetailResp`，与 detail 同构。
- 导出票据用 `@JsonInclude(NON_NULL)` 承载仅档案导出才有的 `mode`。
- `putPrefs` 请求改为 `PrefsPatchReq`（8 个可空字段）；未知键仍由服务层静默忽略，与既有兼容语义一致。
- 测试更新仅为 Map#get → record accessor / 结构化断言，无口径变化。

### 10.4 契约产物

- `backend/openapi/openapi.json` 重新生成；`frontend` 与 `miniprogram` 的 `schema.d.ts` 同步 `gen:api` 并入库。

### 10.5 门禁结果

- 后端 `mvn test`：401 例，0 失败，9 跳过。
- Golden：15 场景全部执行；写接口回执字段（taskNo/status、starred、feedback、fileId、sessionId、simulateId 等）与改造前契约一致。
- 前端 lint/format/typecheck/test（108）/build 全通过；小程序 lint/format/typecheck/H5/mp-weixin 全通过。
- `check_c_guard` PASS（仅 Batch 2 已批准的 C-10 两项豁免）。

**Stage 3C 门禁通过，按“分阶段停靠”暂停，等待确认后进入 3D（B-19 错误码统一）。**

## 11. Stage 3D 执行结果（B-19 错误码与映射，已完成）

### 11.1 错误码（只增不改）

- 新增 `INTERNAL_ERROR(5000, "服务内部错误")`、`DEPENDENCY_UNAVAILABLE(5002, "依赖服务暂不可用")`。
- 既有 16 个码值全部保持不变；`ErrorCodeMappingTest` 将码值与 HTTP 映射逐项冻结。

### 11.2 统一映射

- 新增 `common/api/ErrorCodeMapping`：错误码 → HTTP 状态的唯一映射，使用穷尽 switch，新增错误码未登记时编译失败。
- `GlobalExceptionHandler`：
  - 未知异常 → HTTP 500 + 5000（A-12 关闭，不再冒充 2001）。
  - `DataAccessResourceFailureException` / `RedisConnectionFailureException` → HTTP 503 + 5002。
  - 缺参、参数类型不匹配、请求体不可解析、Bean Validation 方法级校验 → HTTP 400 + 2001。
  - `LlmUnavailableException` 仍为 503 + 4001、`OutputInvalidException` 仍为 502 + 4003，语义不变。
- `SecurityConfig` 补 `accessDeniedHandler`：URL 级 403 也返回统一 `ApiResponse` 信封（此前为 Spring 默认错误体）；401 entry point 原已统一。
- 前端 `http.ts` 未改：继续按非零码拒绝并透传 `msg`。

### 11.3 契约测试与运行时验证

- 新增 `ErrorCodeMappingTest`（6 例）：18 个错误码的码值与 HTTP 映射全量冻结；未知异常、依赖不可达、业务异常、缺参、上传超限的 body.code/HTTP 状态逐一断言。
- 运行时冒烟（真实后端）：401/1001、400/2001（空 body、坏 JSON、业务参数、缺参、类型不匹配）、404/2002、403/1002 全部符合预期。

### 11.4 门禁结果

- 后端 `mvn test`：407 例，0 失败，9 跳过。
- Golden：15 场景全部执行，成功路径契约无变化。
- 前端 lint/format/typecheck/test（108）/build、小程序 lint/format/typecheck/H5/mp-weixin 全通过；`gen:api` 无契约漂移。
- `check_c_guard` PASS（仅 Batch 2 已批准的 C-10 两项豁免）。

**Batch 3（B-01 / B-15 / B-19）全部完成；3E（B-02）按批准延后。**
