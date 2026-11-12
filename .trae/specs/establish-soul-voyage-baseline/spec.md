# 心屿漫行（soul-voyage）全局基线 Spec

> **文档定位**：本文件是「心屿漫行」后续开发、重构、UI 优化、功能扩展的**唯一执行基准**。
> **基线版本**：v1.0 · 2026-11-11 · 基线提交 `bc26bbe`
> **当前状态**：已评审通过并冻结（2026-11-11）；本轮只产出基线文档，未修改任何业务代码
> **文档语言**：中文
> **适用仓库**：`soul-backend/`、`soul-frontend/`、`miniprogram/`、`deploy/`、`docs/`

## 0. 治理规则（先读这一节）

1. **唯一基准**：后续任务的范围、方案、验收均以本 Spec 为准。与旧文档冲突时，工程执行口径以本 Spec 为准；产品红线、安全语义仍以《项目手册》+ 本 Spec §3.4 为准。
2. **不破坏原有业务逻辑**：任何重构前后，同一输入必须产生同一业务结果（状态机、风险判定、加密落库、报告内容、接口字段、页面行为均不得漂移）。
3. **核心业务不自主修改**：本 Spec §3.3（谨慎重构）与 §3.4（禁止改动）中的任何代码，未获得用户逐项书面确认前，不得实施。
4. **变更登记**：任何新增/调整需求，先在本 Spec 中登记变更（ID、影响模块、风险范围、验收方式），再进入开发。
5. **文档即契约**：接口字段、错误码、设计 Token、页面行为一经写入本 Spec，代码必须与之对齐；实现与 Spec 不一致时，以 Spec 为验收依据。
6. **禁止回滚用户改动**：工作区可能存在与本 Spec 无关的用户改动，任何重构不得撤销、覆盖或回退这些改动。

---

# 1. 项目概况

## 1.1 背景与定位

「心屿漫行 soul-voyage」是面向大学生群体的**任务驱动型多智能体协同心理自助成长平台**。它不做心理诊断、不替代心理咨询师、不提供药物建议；在高危信号出现时只做**风险筛查、加密归档与转介提示**。

产品以两个入口承载完整闭环：

- **情绪日记 / 认知书写**：觉察 → 溯源 → 疏导 → 风险归档 → 报告与练习。
- **人际模拟训练 / 树洞漫聊**：安全试错 → 逐轮反馈 → 复盘 → 风险收口。

核心闭环：
```
觉察（情绪识别） → 溯源（压力源/认知误区） → 训练（人际模拟/自助练习） → 疏导（方案/计划） → 归档（报告/画像/风险）
```

## 1.2 用户角色与边界

| 角色 | 核心诉求 | 主要功能 |
|---|---|---|
| 大学生（主用户） | 情绪无处安放、人际冲突不会处理、压力大不知源头 | 日记、洞察、模拟训练、练习、漫聊、成长档案 |
| 高危边缘用户 | 已出现明显心理危机信号 | 被识别 → 关怀模式 → 转介资源；不做诊断 |
| 管理员 | 运营、内容维护、风险复核、审计 | 管理端工作台（任务/风险/内容/用户/审计/群体看板） |
| 辅导员（群体视角） | 只看到匿名聚合 | 群体看板（≥10 人授权才展示，密文不出查询） |

**产品红线（不可突破）**：

- 不做心理疾病诊断，不下「抑郁症/焦虑症/PTSD」等结论。
- 不替代心理咨询师/治疗行为，不提供药物建议。
- 未经用户授权，不将个体数据用于群体展示。
- 高危信号必须走确定性规则轨兜底，不能只依赖 LLM。
- 所有 AI 生成内容页面必须展示「自助参考，不构成医学诊断」免责声明。

## 1.3 技术栈

### 后端

| 项 | 版本/选型 | 说明 |
|---|---|---|
| 语言 | Java 21 | 启用虚拟线程（I/O 密集的 LLM 调用） |
| 框架 | Spring Boot 3.3.5 | 模块化单体，不拆微服务 |
| 安全 | Spring Security + JJWT 0.12.6 | JWT access/refresh、epoch 吊销、BCrypt |
| 持久化 | Spring Data JPA + MySQL 8.4 | `ddl-auto=none`，`deploy/sql/schema.sql` 为表结构唯一事实源 |
| 缓存/状态 | Redis 7 | 会话上下文、任务事件流、限流、JWT 黑名单 |
| 图数据库 | Neo4j 5.26 + neo4j-java-driver 5.26.0 | 可选；MySQL `kg_node` 是唯一写入口，图是只读派生视图 |
| 契约 | springdoc-openapi 2.6.0 + networknt json-schema-validator 1.5.1 | `/v3/api-docs`、Agent 输出闭集校验 |
| 观测 | Micrometer + Prometheus + Actuator | `/actuator/health` 公开，其余需鉴权 |
| 测试 | JUnit 5 + Spring Boot Test + H2 + JaCoCo 0.8.12 | H2 回归，Neo4j 契约测试可选跑 |
| 构建 | Maven 3.9 | 中文路径下用 `java -jar` 运行，避免 `spring-boot:run` fork ClassNotFound |

### 前端（Web）

| 项 | 版本/选型 | 说明 |
|---|---|---|
| 框架 | Vue 3.5 + TypeScript 6.0 | `<script setup lang="ts">` |
| 构建 | Vite 8.3 | 路由级懒加载，ECharts 独立分块 |
| 路由 | Vue Router 5.3 | history 模式，路由 meta 控制鉴权/深度/管理端 |
| 状态 | Pinia 4.0 | auth/content/crisis/ui 四个 store |
| 请求 | Axios | 统一 `ApiResp` 信封、401 单飞刷新 |
| 流式 | fetch + ReadableStream（SSE） | EventSource 无法带 Authorization 头 |
| 图表 | ECharts 6.1 | 仅 Insights 使用，按需引入 |
| 工具 | VueUse 15 | 主题、颜色模式 |
| 测试 | Vitest 5 + happy-dom + @vitest/coverage-v8 | 逻辑层单测 |
| 规范 | ESLint 10 + Prettier 3 + vue-tsc | CI 强制 lint/format/typecheck/build |

### 小程序端

| 项 | 版本/选型 | 说明 |
|---|---|---|
| 框架 | uni-app 3.0（Vue 3） | 微信小程序为主，H5 为目标验证环境 |
| 状态 | Pinia 2 | 与 Web 端独立 |
| 构建 | Vite 5.2 | `build:mp-weixin` / `build:h5` |
| 类型 | TypeScript 5.6 + vue-tsc | CI 类型检查 |
| 契约 | 同一份 `soul-backend/openapi/openapi.json` | 与 Web 共用契约 |

## 1.4 运行拓扑

```
浏览器 / 微信小程序
        │  HTTPS（生产待落地） · REST + SSE
        ▼
Nginx（deploy/nginx.conf）
  ├─ 静态资源（soul-frontend/dist）
  ├─ /api/ 反代 → app:8080（SSE 关缓冲、读超时 3600s）
  └─ /actuator/health
        │
        ▼
Spring Boot 单体（app:8080）
  ├─ Auth / Account / Admin
  ├─ Orchestrator（任务状态机 + Pipeline + SSE）
  ├─ Agents（EMOTION / TRACE / SUPPORT / SIMULATE / RISK_ARCHIVE / COMPANION / LETTER）
  ├─ LLM Gateway（mock | openai 兼容）
  ├─ ASR Gateway（mock | openai 兼容）
  ├─ KG（DbKgService | Neo4jKgService）
  ├─ Crypto（信封加密）
  └─ ContentStore（DB 快照 + content:version）
        │
        ├─ MySQL 8.4（业务主库，36 张表）
        ├─ Redis 7（会话/任务事件/限流/黑名单/导出快照）
        └─ Neo4j 5.26（可选，KG 只读派生视图）
```

## 1.5 仓库结构与规模

```
soul-backend/      Spring Boot 单体（Maven）
soul-frontend/     Vue3 + TS + Vite Web 端
miniprogram/  uni-app + Vue3 + TS 微信小程序端
deploy/       schema.sql（唯一事实源）/ migrate_mN.sql / neo4j seed / tools（smoke/walk/load/stub）
docs/         项目手册.md（产品与设计唯一事实源）· demo/V2交互原型.html
.github/      CI（backend / frontend / miniprogram 三段）
```

代码规模基线（不含生成文件与构建产物）：

| 区域 | 行数 | 说明 |
|---|---:|---|
| 后端主代码 | 13,584 | 212 个 Java 文件 |
| 后端测试 | 9,428 | 47 个测试类 |
| 前端源码 | 14,235 | 其中 views 9,657；components 1,940；styles 250 |
| 小程序源码 | 3,397 | 9 个页面 |
| 数据库表 | 36 | `deploy/sql/schema.sql` |
| OpenAPI 操作 | 106 | 94 个 path |

## 1.6 当前状态与已知缺口

**已完成里程碑**（以 README / 项目手册为准）：

- M0–M4 基础盘：骨架、建库、登录、调度中心、LLM 网关（Mock）、情绪 Agent、AgentFlow、溯源 + KG、复盘报告、人际模拟、风险归档、成长档案。
- M5–M10 V2 深化：安全闭环、日记本、iOS 设计系统、成长陪伴、内容生态、管理端、真实 LLM 网关。
- M11–M14：语音日记、辅导员群体看板、多节点状态面、微信小程序端。
- M16 / M18：Neo4j 实装、图模式收口（可观测/对账/压测）。
- M19：覆盖率度量与前端测试。

**当前测试基线**：

- 后端：47 个测试类；当前提交记录/CI 口径为 401 例（9 例 `Neo4jKgContractTest` 在无真图实例时跳过）。README §测试基线仍写 151 例，属文档漂移，Batch 1 修正。
- 前端：10 个 spec 文件、100 例；逻辑层分支覆盖 100%。
- CI：后端 `RiskRulesTest` 卡点 + 全量测试 + JaCoCo check + 打包；前端 lint/format/coverage/build/gen:api diff；小程序 lint/format/typecheck/build/gen:api diff。

**已知缺口（影响后续扩展，不是本轮修复项）**：

1. **M15 上线收口未完成**：真实 LLM/ASR 密钥、HTTPS/域名/备案、小程序 `ONLINE_ORIGIN`、Docker 生产部署（Neo4j 服务未编排）。
2. **UI 与目标风格不一致**：现状是 iOS 风格（大圆角、阴影、毛玻璃、渐变、emoji），目标是「静室手记（Quiet Journal）」的暖纸、衬线阅读、低刺激视觉，见 §5。
3. **接口契约偏弱**：大量 `Map<String,Object>` 返回，OpenAPI 无法描述字段；前端手写类型与生成类型并存。
4. **多节点生产化不足**：审计链、定时任务、登录失败锁定、密钥创建在多实例下存在一致性风险。
5. **前端缺少端到端 UI 自动化**：目前依赖单测 + 脚本走查；小程序无单测。

## 1.7 文档关系

| 文档 | 角色 | 与本 Spec 的关系 |
|---|---|---|
| `docs/项目手册.md` | 产品/安全/设计的唯一事实源 | 产品边界、风险语义、数据加密规范不得违背；工程执行以本 Spec 为准 |
| `docs/demo/V2交互原型.html` | M6 视觉基线 | 本 Spec §5 将取代其视觉方向；交互结构可参考 |
| `soul-backend/openapi/openapi.json` | 接口契约快照 | 本 Spec §2.7 为清单；字段契约以生成文件 + 本 Spec §3.3 B-01 为准 |
| `deploy/sql/schema.sql` | 表结构唯一事实源 | 本 Spec §2.6 为关系说明；表结构变更必须新增迁移文件，不改历史语义 |
| 本 Spec | 后续开发/重构/UI/扩展唯一执行基准 | 最高优先级（工程执行） |

---

# 2. 现有模块清单

## 2.1 总体分层

```
接入层    Web（Vue3）· 小程序（uni-app）· 管理端（同一 Web 壳，宽屏变体）
协议层    REST /api/v1/* · SSE（任务流、逐轮对话）
应用层    Controller → Application Service → Domain Service
编排层    Orchestrator（任务状态机、Pipeline、Step、AgentMessage、SSE）
智能体层  EMOTION / TRACE / SUPPORT / SIMULATE / RISK_ARCHIVE / COMPANION / LETTER
能力层    LLM Gateway · ASR Gateway · KG（DB/Neo4j）· Crypto · OutputValidator · ContentStore
数据层    MySQL（业务/密文）· Redis（状态/事件/限流）· Neo4j（可选图）· 内存/Redis 导出快照
```

## 2.2 后端模块清单

> 包根：`soul-backend/src/main/java/com/leyon/soul/`

| 包 | 关键类 | 职责 | 对外入口 |
|---|---|---|---|
| `common.api` | `ApiResponse`、`ErrorCode`、`PageReq`、`PageResp`、`OpenApiConfig` | 统一响应信封、错误码、分页、OpenAPI | 全接口 |
| `common.exception` | `BizException`、`GlobalExceptionHandler` | 业务异常 → HTTP 状态码 + 统一 body | 全接口 |
| `common.util` | `Ulid`、`TraceIdFilter` | 任务号/消息号、traceId 注入 MDC | 全接口 |
| `common.time` | `BusinessCalendar` | 业务时区（默认 Asia/Shanghai）、业务日/周 | 全局 |
| `common.state` | `SlidingWindowLimiter`、`InProcessWindowLimiter`、`RedisWindowLimiter`、`RedisStateConfig` | 登录失败滑动窗；M13 分布式状态 | 登录/限流 |
| `auth` | `AuthController`、`AuthService`、`JwtService`、`JwtAuthFilter`、`SecurityConfig`、`PermissionService`、`RbacSeeder`、`WechatAuthController/Service` | 注册/登录/刷新/登出、JWT、RBAC、微信绑定 | `/api/v1/auth/**` |
| `account` | `AccountController`、`AccountService` | 注销冷静期、撤回、改密、全量数据导出 | `/api/v1/users/me/**` |
| `crypto` | `CryptoService`、`EnvelopeCryptoService`、`MasterKeyProvider`、`DataKeyEntity/Repository` | 用户级 DEK 信封加密、AES-256-GCM、注销销毁密钥 | 全局内部 |
| `audit` | `AuditLogEntity/Repository`、`AuditService` | 审计日志 + SHA-256 哈希链 + 全链校验 | 管理端 |
| `orchestrator` | `OrchestratorService`、`AgentRegistry`、`AgentCode` | 任务提交/执行/重试/取消、步骤重试、消息落库、SSE 发布 | `/api/v1/tasks/**` |
| `orchestrator.pipeline` | `TaskDefinition`、`StepSpec`、`TaskContext`、`PipelineRegistry`、`DiaryPipeline`、`CognitivePipeline`、`SimulatePipeline`、`GrowthLetterPipeline` | 声明式流水线；按危机态动态裁剪步骤 | 任务提交 |
| `orchestrator.sse` | `TaskEventBus`、`TaskEventLog`、`InMemoryEventLog`、`RedisEventLog`、`EventRelay`、`RedisEventRelay` | SSE 订阅、心跳、断线重放、跨节点中继 | `/tasks/{taskNo}/stream` |
| `orchestrator.protocol` | `AgentMessage` | REQUEST / MIDDLE_RESULT / FINAL / ERROR 结构化消息 | Agent 间 |
| `orchestrator.agent` | `Agent`、`AgentRuntime`、`LlmUnavailableException`、`OutputInvalidException` | Agent 抽象与运行时句柄 | Agent 层 |
| `agent.emotion` | `EmotionAgent` | 情绪识别、16 情绪闭集、效价/强度、事件标签 | EMOTION 步骤 |
| `agent.trace` | `TraceAgent` | 压力源、认知误区、苏格拉底追问、复盘正文 | TRACE 步骤 |
| `agent.support` | `SupportAgent`、`ExerciseCatalog`、`Exercise`、`ExerciseController` | 疏导方案、练习匹配、认知书写、练习打卡 | `/exercises`、`/exercise-records` |
| `agent.simulate` | `SimulationController/Service`、`NpcDirector`、`SceneCatalog`、`SceneCard`、`SimulateReviewAgent` | 场景选择、逐轮 NPC、导演状态、复盘评分 | `/scenes`、`/simulations/**` |
| `agent.risk` | `RiskController`、`RiskRules`、`RiskArchiveAgent` | 双轨风险研判、风险事件归档、转介资源 | `/risk/resources` |
| `agent.companion` | `CompanionController/Service`、`CompanionAgent`、`CompanionPipeline`、`CompanionContextAssembler`、`CompanionMood`、`CompanionSweeper` | 树洞漫聊、分段封口、逐轮危机拦截、异步消化 | `/companion/**` |
| `agent.letter` | `LetterAgent`、`GrowthLetterService`、`LetterController` | 每周成长来信生成与读取 | `/letters` |
| `domain.diary` | `DiaryController/Service`、`DiaryEntity/Repository`、`DraftEntity/Repository`、`VoiceDiaryController` | 日记 CRUD、重分析、草稿、语音转写 | `/diaries/**` |
| `domain.emotion` | `EmotionController`、`EmotionCatalog`、`EmotionTrajectoryEntity/Repository` | 情绪轨迹、自评、周画像 | `/emotions/**` |
| `domain.checkin` | `MoodCheckInController/Service`、`CheckInService`、`StreakService` | 每日心情打卡、补签、连续天数 | `/mood-check-ins/**` |
| `domain.companion` | `CompanionSessionEntity/Repository`、`CompanionTurnEntity/Repository` | 漫聊持久化 | 内部 |
| `domain.simulate` | `SimulateSessionEntity/Repository`、`SimulateTurnEntity/Repository` | 模拟会话/轮次持久化 | 内部 |
| `domain.report` | `ReportController`、`ReportEntity/Repository` | 报告读取、收藏、反馈 | `/reports/**` |
| `domain.archive` | `ArchiveController`、`ArchiveService` | 档案汇总、导出快照 | `/archive/**` |
| `domain.plan` | `PlanController`、`PlanService`、`GrowthPlanEntity`、`PlanItemEntity` | 成长计划生成、跟练、顺延、到期小结 | `/plans/**` |
| `domain.achievement` | `AchievementController/Service`、`AchievementCatalog` | 成就评估与展示 | `/achievements` |
| `domain.notify` | `NotificationController/Service`、`PreferencesService`、`ReminderScheduler` | 站内通知、偏好、提醒扫描 | `/notifications`、`/preferences` |
| `domain.crisis` | `CrisisService`、`CrisisState`、`CrisisLifecycleEntity/Repository`、`DailySweeper` | 危机生命周期状态机、每日巡检 | 内部 |
| `domain.risk` | `RiskEventEntity/Repository` | 风险事件持久化 | 内部 |
| `domain.content` | `ContentStore`、`ContentDataSeeder`、`KgNodeEntity/Repository`、`SceneCardEntity/Repository`、`ExerciseLibraryEntity/Repository`、`ReadingService/Controller`、`UserFavorite*`、`UserReadLog*` | 内容快照、种子导入、每日一读、收藏 | `/readings/**` |
| `domain.board` | `GroupBoardService`、`SupportGroupEntity`、`SupportGroupMemberEntity` | 群体、成员、匿名聚合（隐私三闸） | 管理端 |
| `domain.export` | `ExportStore`、`InMemoryExportStore`、`RedisExportStore`、`ExportRecordEntity/Repository` | 一次性导出快照 | `/archive/export`、`/users/me/data-export` |
| `domain.profile` | `EmotionProfileEntity/Repository` | 周维度情感画像 | 内部 |
| `domain.task` | `TaskInstanceEntity/Repository`、`TaskStepLogEntity/Repository`、`AgentMessageEntity/Repository` | 任务/步骤/消息持久化 | 内部 |
| `domain.user` | `UserEntity/Repository` | 用户、状态、危机态、注销时间、微信 openid | 内部 |
| `llm` | `LlmClient`、`MockLlmClient`、`OpenAiCompatibleClient`、`LlmGuard`、`LlmGate`、`InProcessGate`、`RedisGate`、`OutputValidator`、`PromptTemplates`、`LlmUsageCollector`、`StreamedJsonField` | 模型网关、限流闸门、Prompt、输出校验、Token 统计 | Agent 层 |
| `kg` | `KgSearchService`、`DbKgService`、`Neo4jKgService`、`Neo4jKgConfig` | KG 召回；DB/Neo4j 双实现；图不可用秒级回落 | Agent 层 |
| `asr` | `AsrClient`、`MockAsrClient`、`OpenAiCompatibleAsrClient` | 语音转写供应商抽象 | `/diaries/voice-transcriptions` |
| `wechat` | `WxAuthClient`、`MockWxAuthClient`、`HttpWxAuthClient`、`WxBindTicketStore` | 微信 code2session、一次性绑定票 | `/auth/wechat/**` |
| `admin` | `AdminTaskController`、`AdminRiskController`、`ContentAdminController`、`AdminSupportController`、`AdminOpsController`、`AdminMetricsController`、`AdminBoardController` | 管理端任务/风险/内容/用户/审计/指标/群体看板 | `/api/v1/admin/**` |

**模块现状判断**：

- 职责划分总体清晰，Agent / 编排 / 领域 / 能力层已分离。
- 但**分层不彻底**：`domain.*` 包内同时存在 Controller、Service、Entity、Repository；`admin` 控制器直接依赖 Repository；`TaskController` 直接依赖 `DiaryService`。
- 对外返回大量 `Map<String,Object>`，接口契约不可静态描述。
- 详见 §3.2 / §3.3 / §3.5。

## 2.3 前端模块清单

### 路由与页面（26 个视图）

| 路由 | 视图文件 | 层级 | 功能 | 关键依赖 |
|---|---|---|---|---|
| `/login` | `LoginView.vue` | 公开 | 登录/注册切换 | `auth` store |
| `/today` | `TodayView.vue` | Tab | 今日首页：打卡、计划、每日一读、漫聊/日记入口、最近动态 | checkin/plan/reading/task/companion |
| `/diaries` | `DiaryListView.vue` | Tab | 日记时间线、搜索、删除 | `/diaries` |
| `/diaries/write` | `DiaryWriteView.vue` | 二级 | 写日记、心情自评、语音转写 | `/tasks`、`/diaries/voice-transcriptions` |
| `/diaries/analysis` | `AnalysisView.vue` | 二级 | 任务流式分析：情绪/溯源/疏导/风险回执 | `/tasks`、SSE |
| `/diaries/:id` | `DiaryDetailView.vue` | 二级 | 日记详情、关联报告、编辑/删除/重分析 | `/diaries/{id}` |
| `/companion` | `CompanionView.vue` | 二级 | 树洞漫聊、逐轮流式回复、收段 | `/companion/**`、SSE |
| `/practice` | `PracticeView.vue` | Tab | 练习首页：模拟入口、进行中计划、练习库、足迹 | `/plans`、`/exercises` |
| `/practice/sim` | `SimulationView.vue` | 二级 | 场景选择 → 对话 → 复盘三阶段 | `/scenes`、`/simulations/**`、SSE |
| `/practice/room/:id` | `PracticeRoomView.vue` | 三级 | 沉浸跟练：呼吸环 / 54321 / 认知书写 | `/exercises`、`/exercise-records`、`/tasks` |
| `/insights` | `InsightsView.vue` | Tab | 情绪曲线、压力源、误区趋势、沟通雷达、周画像 | `/emotions/**`、`/reports` |
| `/archive` | `ArchiveView.vue` | 二级 | 成长档案、报告列表、收藏、导出快照 | `/archive/**` |
| `/archive/report/:id` | `ReportDetailView.vue` | 三级 | 报告详情、反馈、收藏 | `/reports/{id}` |
| `/letters` | `LettersView.vue` | 二级 | 成长来信列表 | `/letters` |
| `/achievements` | `AchievementsView.vue` | 二级 | 成就墙 | `/achievements` |
| `/notifications` | `NotificationsView.vue` | 二级 | 通知中心、已读 | `/notifications` |
| `/me` | `MeView.vue` | Tab | 我的：偏好、主题、触感、入口 | `/preferences` |
| `/account` | `AccountView.vue` | 二级 | 隐私中心：导出、改密、注销 | `/users/me/**` |
| `/crisis` | `CrisisView.vue` | 公开 | 危机资源页 | `/risk/resources` |
| `/admin` | `admin/AdminHomeView.vue` | 管理端 | 指标总览、Token 成本、Agent 成本 | `/admin/metrics/overview` |
| `/admin/tasks` | `admin/AdminTasksView.vue` | 管理端 | 任务监控、步骤时间线、失败重跑 | `/admin/tasks/**` |
| `/admin/risk` | `admin/AdminRiskView.vue` | 管理端 | 风险队列、证据二次授权解密、结案 | `/admin/risk/**` |
| `/admin/content` | `admin/AdminContentView.vue` | 管理端 | KG/场景/练习热更新、预览试跑 | `/admin/content/**` |
| `/admin/users` | `admin/AdminUsersView.vue` | 管理端 | 用户查询、冻结/解冻、危机看板 | `/admin/users/**` |
| `/admin/ops` | `admin/AdminOpsView.vue` | 管理端 | 审计日志、哈希链校验、密钥、导出留痕 | `/admin/ops/**` |
| `/admin/board` | `admin/AdminBoardView.vue` | 管理端 | 群体、成员、匿名聚合看板 | `/admin/board/**` |

### 组件与基础设施

| 目录 | 内容 | 说明 |
|---|---|---|
| `components/ui` | `SvBubble`、`SvCalendarHeat`、`SvCard`、`SvCell`、`SvCheckbox`、`SvChip`、`SvDialogHost`、`SvDisclaimer`、`SvEmotionDial`、`SvIcon`、`SvList`、`SvNavBar`、`SvProgressRing`、`SvPullToRefresh`、`SvSegmented`、`SvSheet`、`SvSkeleton`、`SvSwipeActions`、`SvSwitch`、`SvTabBar`、`SvTimeline`、`SvToastHost` | 自研 iOS 风格组件库 |
| `components` | `AdminShell`、`CrisisReferral`、`SvCrisisBanner` | 管理端壳、危机转介、全局横幅 |
| `stores` | `auth`、`content`、`crisis`、`ui` | 会话、内容目录、危机资源/画像、Toast/Dialog/触感 |
| `composables` | `theme`、`voiceNote` | 主题、录音/转写 |
| `api` | `http.ts`、`sse.ts`、`admin.ts`、`schema.d.ts` | Axios 封装、SSE、管理端类型、OpenAPI 生成类型 |
| `styles` | `tokens.css`、`base.css` | 设计 Token、全局基础样式 |
| `utils` | `emotions.ts` | 16 情绪元数据 |
| `directives` | `haptic.ts` | 触感反馈 |

### 前端现状判断

- 路由分层（tab 深度 0 / push 1 / 2）与转场设计完整。
- 组件库覆盖度高，但**页面级重复**严重：每个页面重复 `.body`、`.t`、`.err`、`.ok`、`.center`、列表行样式。
- **巨型视图**：`SimulationView` 935 行、`TodayView` 800、`PracticeRoomView` 690、`ReportDetailView` 647、`ArchiveView` 541。
- **类型重复**：`PlanView/PlanItem` 在 Today/Practice 各写一遍；`Point/Week` 在 Insights 等重复；`schema.d.ts` 仅 `admin.ts` 使用。
- 视觉风格为 iOS 风（大圆角、阴影、毛玻璃、渐变、emoji），与目标「静室手记」的暖纸、衬线阅读、低刺激方向冲突。

## 2.4 小程序端模块清单

| 页面 | 文件 | 功能 |
|---|---|---|
| 登录 | `pages/login/index.vue` | 微信登录/绑定/注册、口令登录 |
| 今日 | `pages/today/index.vue` | 打卡、连续天数、每日一读、漫聊入口 |
| 日记列表 | `pages/diary/index.vue` | 时间线、搜索、下拉刷新 |
| 写日记 | `pages/diary/write.vue` | 文本/语音、提交任务 |
| 日记详情 | `pages/diary/detail.vue` | 原文、报告入口 |
| 树洞 | `pages/companion/index.vue` | 漫聊、流式回复 |
| 我的 | `pages/me/index.vue` | 偏好、主题、入口 |
| 通知 | `pages/notifications/index.vue` | 通知列表 |
| 求助资源 | `pages/resources/index.vue` | 危机资源 |

基础设施：`api/app.ts`（按域封装的接口）、`api/http.ts`、`api/sseText.ts`、`api/wx.ts`、`api/schema.d.ts`、`stores/auth.ts`、`composables/theme.ts`、`styles/tokens.scss`、`styles/base.scss`。

**定位**：微信只做既有账号的第二入口，不另立账号体系；一次性绑定票 `WxBindTicketStore` 是凭证。

## 2.5 业务流程

### BF-1 情绪日记全链路（DIARY_PIPELINE）

```
用户写日记
  → POST /api/v1/tasks { pipelineCode: "DIARY_PIPELINE", payload: { diaryText, recordDate, moodSelfRating, ... } }
  → Orchestrator.submit：幂等（clientReqId）、单用户活跃任务 ≤2、落库 PENDING
  → TaskController 同步调用 DiaryService.attachTask：日记密文落库、任务关联
  → 虚拟线程执行：
       EMOTION（情绪识别，写 emotion_trajectory）
       → TRACE（压力源/误区，写 report(type=TRACE)）
       → SUPPORT（疏导方案，写 report(type=SUPPORT) + 计划）
       → RISK_ARCHIVE（双轨研判，写 risk_event / 危机态 / 周画像，产出回执）
  → 每步：task_step_log + agent_message(密文) + SSE 事件
  → 前端 /diaries/analysis 订阅 SSE，逐步渲染；完成后报告入档案
```

- 危机态 `CRISIS` 时流水线裁剪为 `EMOTION → RISK_ARCHIVE` 两步。
- 输出校验失败：该步 `DEGRADED`，任务 `PARTIAL_SUCCESS`。
- 模型不可用：同一步最多 3 次（首次 + 2 重试，退避 2s/8s），仍失败任务 `FAILED`。
- 编辑/删除日记：关联报告置 `stale=1`；可 `POST /diaries/{id}/reanalyze` 复用流水线重跑。

### BF-2 认知书写（COGNITIVE_PIPELINE）

```
三栏书写（情境/自动想法/替代想法）+ 情绪强度
  → POST /exercises/cognitive-writing 创建 COGNITIVE_PIPELINE 任务
  → EMOTION → TRACE（苏格拉底追问）→ RISK_ARCHIVE
  → 前端轮询/流式获取追问；完成后计入练习记录
```

### BF-3 人际模拟（SIMULATE_PIPELINE + NpcDirector）

```
选择场景/难度 → POST /simulations 创建会话（RUNNING）
  → 逐轮 POST /simulations/{id}/turns（SSE）：
       NpcDirector 读场景人设/目标维度/情绪档位，生成 NPC 回复
       逐轮危机词表命中 → crisis_flag=1，SSE crisis 事件，温和中止
  → POST /simulations/{id}/finish → SIMULATE_PIPELINE：
       SIMULATE（四维评分：LISTEN/BOUNDARY/EMPATHY/CONCESSION，写 report）
       → RISK_ARCHIVE（SIMULATE_BREAKOUT 规则轨 + 语义轨）
  → 前端渲染复盘：综合分、逐轮评分、关键时刻、NVC 改写、参考案例
```

### BF-4 树洞漫聊（COMPANION_PIPELINE）

```
POST /companion/sessions（当日分段，自然日 + 30min 静默）
  → 逐轮 POST /companion/sessions/{id}/turns（SSE）：
       CompanionAgent 生成陪伴回复；逐轮 RiskRules HIGH 一票拦截
       用户可对单轮标记「这句别分析」（no_analyze）
  → 静默 30min 或 POST /end → 封段 SEALED
  → 异步 COMPANION_PIPELINE：
       COMPANION（摘要/digest）
       → EMOTION（source_type=CHAT）
       → [wantTrace 时 TRACE]
       → RISK_ARCHIVE
  → 状态 DIGESTED
```

### BF-5 风险双轨与危机生命周期

```
规则轨：RiskRules.scan(text) → HIGH / MEDIUM；否定/引述语境降级 + needsReview
语义轨：上游 Agent 已落库的 report.riskLevel
  → 取最高级别；HIGH 一票升级
  → risk_event（证据密文） + crisis_lifecycle（append-only）
  → CrisisService：
       NORMAL → CRISIS（HIGH_DETECTED，7 天强干预窗口）
       COOLING → CRISIS（RE_ENTRY）
       CRISIS → COOLING（COOLDOWN_EXPIRED；累计 2 次 → REVIEW）
       COOLING → NORMAL（连续 2 个完整自然周 avgValence > -0.3 且无新风险事件）
       REVIEW → NORMAL（管理员结案）
  → 前端 CrisisReferral / CrisisView 展示转介资源
```

### BF-6 打卡、计划、成就、通知、来信

```
每日打卡 → mood_check_in（唯一键 user+date）
  → StreakService 计算连续/最长/总天数
  → AchievementService 评估成就
  → 补签：每自然月 1 次（后端裁决）

SUPPORT 报告 → PlanService 生成 growth_plan + plan_item（3/5/7 天）
  → 用户跟练 → exercise_record（同日同练习幂等）
  → 完成回写 plan_item.done_at
  → 到期 DailyPlanSummary 生成小结

ReminderScheduler 每小时扫描：
  → 打卡提醒（当日 dedup）
  → 计划提醒（计划×日期 dedup）
  → NotificationService 频率护栏（≤2 条/日）

GrowthLetterService 每周一 07:00：
  → 组装本周确定性素材（打卡/日记/练习/报告）
  → GROWTH_LETTER_PIPELINE（LETTER 单步 LLM）
  → growth_letter（密文）
```

### BF-7 档案与导出

```
GET /archive/summary → 本周周报 + 报告 + 情绪点 + 训练分 + 收藏
POST /archive/export → ArchiveService 生成一次性快照（ExportStore）
GET /archive/export/{fileId} → 领取后作废
GET /users/me/data-export → 全量个人数据 JSON 快照
POST /users/me/data-export/{fileId} → 一次性领取
```

### BF-8 内容与 KG

```
启动：ContentDataSeeder 从 classpath JSON 导入空表
  → kg_node / scene_card / exercise_library 成为运行时真源
  → content_meta['content:version'] 为失效信号
  → ContentStore 5s TTL 比对版本，30s 轮询兜底
  → Neo4jKgService 按 content:version 懒同步（只读派生视图）
  → 图不可用：2s 驱动超时 + 30s 短路熔断 → 回落 DbKgService（kg_node）
管理端：
  PUT /admin/content/{kg|scenes|exercises}/{code} → upsert + bump version + audit
  POST /admin/content/refresh → 强制 bump
  POST /admin/content/preview → Prompt 预览试跑 + 审计
```

### BF-9 认证与账号

```
注册/登录 → BCrypt 校验 → JWT access(30m)+refresh(7d) → Redis 登录失败计数
刷新 → 旧 refresh 拉黑；重用 → epoch 吊销全部会话
登出 → epoch 吊销（覆盖未持有的兄弟令牌）
改密/注销申请/冻结 → revokeAll
注销：status=3 + 7 天冷静期；到期销毁 DEK + 匿名化 + deleted_at
微信：code2session → 已绑定则发 token；未绑定发一次性 bindTicket → 绑定/注册
```

## 2.6 实体与数据关系

### 2.6.1 表清单（36 张）

| 域 | 表 | 用途 | 关键关系/字段 |
|---|---|---|---|
| 用户与权限 | `user` | 用户、状态、危机态、微信 openid | `status` 1正常/2冻结/3注销；`crisis_state`；`wx_openid` 唯一 |
| | `role_permission` | RBAC 权限 | `(role, permission_code)` 唯一 |
| 任务 | `user_session` | 会话（早期设计） | `user_id`、`scene_type` |
| | `task_instance` | 任务实例 | `task_no` ULID；`client_req_id` 幂等；状态机 |
| | `task_step_log` | 步骤日志 | `(task_id, step_seq, attempt)` 唯一；Token/耗时 |
| | `agent_message` | Agent 结构化消息（append-only） | `payload_enc` 密文；`(task_id, step_seq)` |
| 情绪 | `emotion_trajectory` | 情绪时序点 | `(user_id, record_date, source_type, source_id)` 唯一 |
| | `emotion_profile` | 周画像 | `(user_id, stat_week)` 唯一；压力源/误区 TopN |
| 日记 | `diary` | 日记原文（密文） | `content_enc`；`task_id`；`source` TEXT/VOICE |
| | `draft` | 服务端草稿（密文） | `(user_id, kind)` 唯一 |
| 报告/风险 | `report` | AI 报告（密文 JSON） | `task_id`；`biz_ref_id`；`stale/starred/feedback` |
| | `risk_event` | 风险事件（证据密文） | `level`；`trigger_type`；`task_id` |
| | `crisis_lifecycle` | 危机迁移留痕（append-only） | `from_state/to_state/reason` |
| 模拟 | `scene_card` | 场景卡 | `code` 唯一；`persona_json/goal_dimensions` |
| | `simulate_session` | 模拟会话 | `report_id`；`avg_score`；`dimension_scores` |
| | `simulate_turn` | 逐轮记录 | `(simulate_id, turn_no)` 唯一；`crisis_flag` |
| 练习 | `exercise_library` | 练习库 | `code` 唯一；`steps_json` |
| | `exercise_record` | 练习打卡 | `(user_id, exercise_code, check_date)` 唯一；`plan_id/plan_item_seq` |
| 漫聊 | `companion_session` | 漫聊分段 | `(user_id, chat_date, segment_no)` 唯一；`status` |
| | `companion_turn` | 漫聊逐轮 | `(session_id, turn_no)` 唯一；`risk_hit/no_analyze` |
| 成长陪伴 | `mood_check_in` | 每日心情打卡 | `(user_id, check_date)` 唯一；`made_up` |
| | `growth_plan` | 成长计划 | `source_report_id`；`days/status` |
| | `plan_item` | 计划逐日条目 | `(plan_id, seq)` 唯一；`done_at` |
| | `notification` | 站内通知 | `(user_id, dedup_key)` 唯一；`link` |
| | `user_preferences` | 用户偏好 | `(user_id)` 唯一 |
| | `user_achievement` | 成就 | `(user_id, code)` 唯一 |
| | `growth_letter` | 成长来信（密文） | `(user_id, stat_week)` 唯一 |
| 内容/KG | `kg_node` | KG 节点（单表多型） | `code` 唯一；`type` DISTORTION/PSY_TOPIC/COMM_CASE/STRENGTH_TECH |
| | `content_meta` | 内容字典/版本 | `meta_key` 唯一 |
| | `user_read_log` | 阅读记录 | `(user_id, code, read_date)` 唯一 |
| | `user_favorite` | 收藏 | `(user_id, type, ref_code)` 唯一 |
| 群体 | `support_group` | 群体 | `name` 唯一 |
| | `support_group_member` | 群体成员 | `(group_id, user_id)` 唯一 |
| 系统 | `audit_log` | 审计日志 + 哈希链 | `hash_chain`；按 id 串联 |
| | `data_key` | 用户 DEK 登记 | `(owner_user_id, version)` 唯一；status=3 已销毁 |
| | `export_record` | 导出留痕 | `file_ref` ULID；一次性 |

### 2.6.2 关系图（逻辑关系，无数据库外键）

```
user 1 ── N task_instance 1 ── N task_step_log
  │                         └── N agent_message
  ├── N diary ── 0..1 task_instance (diary.task_id)
  ├── N report ── 1 task_instance (report.task_id) ── 0..1 biz_ref (如 simulate_session.id)
  ├── N emotion_trajectory
  ├── N emotion_profile (user_id + stat_week)
  ├── N risk_event
  ├── N crisis_lifecycle
  ├── N simulate_session 1 ── N simulate_turn
  │                    └── 0..1 report (simulate_session.report_id)
  ├── N companion_session 1 ── N companion_turn
  ├── N mood_check_in
  ├── N growth_plan 1 ── N plan_item
  ├── N exercise_record ── 0..1 growth_plan / plan_item
  ├── N notification / user_preferences / user_achievement / growth_letter
  ├── N user_read_log / user_favorite
  ├── N data_key (user_id + version)
  └── N audit_log (user_id)

support_group 1 ── N support_group_member N ── 1 user
kg_node（唯一写入口，MySQL） ── content:version ──▶ Neo4j 只读派生视图
content_meta（content:version / kg:stressor_map）
export_record ──▶ ExportStore（内存/Redis 一次性快照，不落库内容）
```

**数据约定**：

- 敏感字段统一 `*_enc` BLOB，格式 `[1B keyVersion][12B IV][ciphertext][16B tag]`，AES-256-GCM。
- 业务表统一 `deleted_at` 软删除；用户注销走“密钥销毁 + 匿名化”，不是物理删行。
- 时间统一 `DATETIME(3)`，应用写入 UTC；业务日/周以 `Asia/Shanghai` 计算。
- **没有数据库外键**：完整性由应用保证；这是现状约束，不是建议新增外键的授权。

## 2.7 已有接口（106 个操作 / 94 个 path）

> 统一前缀 `/api/v1`；响应信封 `{ code, msg, data, traceId }`；`code=0` 成功。
> 错误码：10xx 认证 / 20xx 参数 / 30xx 任务 / 40xx 模型网关 / 50xx 风控。

### 2.7.1 认证与账号（14）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册并签发 token |
| POST | `/auth/login` | 登录 |
| POST | `/auth/refresh` | refresh 轮换 + 重用检测 |
| POST | `/auth/logout` | 登出（epoch 吊销） |
| GET | `/auth/me` | 当前用户 |
| POST | `/auth/wechat/login` | 微信 code2session 登录 |
| POST | `/auth/wechat/bind` | 微信绑定既有账号 |
| POST | `/auth/wechat/register` | 微信绑定票注册新账号 |
| DELETE | `/auth/wechat/binding` | 解绑微信 |
| POST | `/users/me/delete` | 申请注销 |
| POST | `/users/me/delete/cancel` | 撤回注销 |
| PUT | `/users/me/password` | 修改密码 |
| GET | `/users/me/data-export` | 生成全量数据导出 |
| POST | `/users/me/data-export/{fileId}` | 领取全量数据导出 |

### 2.7.2 任务与 SSE（5）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/tasks` | 提交任务（pipelineCode + payload + clientReqId） |
| GET | `/tasks` | 任务历史（分页/过滤） |
| GET | `/tasks/{taskNo}` | 任务详情 + 最终产物 |
| GET | `/tasks/{taskNo}/stream` | SSE 事件流（支持 Last-Event-ID） |
| POST | `/tasks/{taskNo}/cancel` | 取消任务 |

### 2.7.3 日记/语音/草稿（9）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/diaries` | 列表（from/to/q/page/size） |
| GET | `/diaries/{id}` | 详情（原文 + 情绪 + 报告元数据） |
| PUT | `/diaries/{id}` | 编辑（置报告 stale） |
| DELETE | `/diaries/{id}` | 软删除（置报告 stale） |
| POST | `/diaries/{id}/reanalyze` | 按新内容重新分析 |
| GET | `/diaries/draft` | 读取服务端草稿 |
| PUT | `/diaries/draft` | 保存草稿 |
| DELETE | `/diaries/draft` | 清除草稿 |
| POST | `/diaries/voice-transcriptions` | 语音转写（multipart） |

### 2.7.4 情绪与打卡（7）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/emotions/trajectory` | 情绪时序点（from/to） |
| GET | `/emotions/profile` | 周画像 + crisisMode |
| POST | `/emotions/self-rating` | 情绪自评 |
| GET | `/mood-check-ins` | 月度打卡 |
| POST | `/mood-check-ins` | 打卡/修改 |
| POST | `/mood-check-ins/makeup` | 补签（每月 1 次） |
| GET | `/mood-check-ins/streak` | 连续/最长/总天数 |

### 2.7.5 模拟训练（8）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/scenes` | 场景目录（含推荐标签） |
| POST | `/simulations` | 创建会话 |
| GET | `/simulations` | 会话列表（状态过滤） |
| GET | `/simulations/{id}` | 会话详情 + 轮次 |
| POST | `/simulations/{id}/turns` | 逐轮对话（SSE） |
| POST | `/simulations/{id}/finish` | 结束并复盘（创建任务） |
| POST | `/simulations/{id}/interrupt` | 中断留档 |
| GET | `/simulations/stats` | 历史最佳/维度 |

### 2.7.6 漫聊（7）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/companion/active` | 当前活跃会话 |
| POST | `/companion/sessions` | 打开/续开当日分段 |
| GET | `/companion/sessions` | 历史会话 |
| GET | `/companion/sessions/{id}` | 会话转录 |
| POST | `/companion/sessions/{id}/turns` | 逐轮对话（SSE） |
| POST | `/companion/turns/{turnId}/no-analyze` | 单轮排除分析 |
| POST | `/companion/sessions/{id}/end` | 收段并触发异步消化 |

### 2.7.7 报告/档案/导出（7）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/reports` | 报告列表（分页/类型） |
| GET | `/reports/{id}` | 报告详情（解密） |
| POST | `/reports/{id}/star` | 收藏/取消 |
| POST | `/reports/{id}/feedback` | 反馈（USEFUL/UNSURE/UNHELPFUL + 备注） |
| GET | `/archive/summary` | 档案汇总 |
| POST | `/archive/export` | 生成一次性档案快照 |
| GET | `/archive/export/{fileId}` | 领取档案快照 |

### 2.7.8 练习/计划/成就/来信/阅读（13）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/exercises` | 练习库 |
| POST | `/exercise-records` | 练习打卡（幂等） |
| GET | `/exercise-records` | 练习记录 |
| POST | `/exercises/cognitive-writing` | 认知书写（COGNITIVE_PIPELINE） |
| GET | `/plans` | 计划列表 |
| GET | `/plans/active` | 进行中计划 |
| GET | `/plans/{id}` | 计划详情 |
| POST | `/plans/{id}/drop` | 放弃计划 |
| GET | `/achievements` | 成就墙 |
| GET | `/letters` | 成长来信 |
| GET | `/readings/today` | 每日一读 |
| POST | `/readings/favorites` | 收藏/取消 |
| GET | `/readings/favorites` | 收藏列表 |

### 2.7.9 通知/偏好（5）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications` | 通知列表 + 未读数 |
| POST | `/notifications/{id}/read` | 单条已读 |
| POST | `/notifications/read-all` | 全部已读 |
| GET | `/preferences` | 偏好 |
| PUT | `/preferences` | 更新偏好（单字段/全量） |

### 2.7.10 风控资源（1）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/risk/resources` | 公开危机资源（无需登录） |

### 2.7.11 管理端（30）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/metrics/overview` | 任务/风险/Agent 成本/Token/反馈/运行时 |
| GET | `/admin/tasks` | 全局任务列表 |
| GET | `/admin/tasks/{taskNo}` | 任务详情 + 步骤 |
| POST | `/admin/tasks/{taskNo}/retry` | 从失败步重跑 |
| GET | `/admin/risk/queue` | 风险复核队列 |
| POST | `/admin/risk/{id}/reveal` | 管理员口令二次授权解密证据 |
| POST | `/admin/risk/{id}/review` | 复核结案（可解除危机态） |
| GET | `/admin/users` | 用户查询（元数据） |
| GET | `/admin/users/crisis-board` | 危机看板 |
| POST | `/admin/users/{id}/freeze` | 冻结并吊销会话 |
| POST | `/admin/users/{id}/unfreeze` | 解冻 |
| GET | `/admin/content/kg` | KG 节点列表 |
| GET | `/admin/content/kg/graph-audit` | DB↔图对账 |
| PUT | `/admin/content/kg/{code}` | KG upsert |
| GET | `/admin/content/scenes` | 场景列表 |
| PUT | `/admin/content/scenes/{code}` | 场景 upsert |
| GET | `/admin/content/exercises` | 练习列表 |
| PUT | `/admin/content/exercises/{code}` | 练习 upsert |
| POST | `/admin/content/refresh` | 强制刷新内容版本 |
| POST | `/admin/content/preview` | Prompt 预览试跑 |
| GET | `/admin/ops/audit` | 审计日志 |
| POST | `/admin/ops/audit/verify` | 全链校验 |
| GET | `/admin/ops/keys` | 数据密钥视图 |
| GET | `/admin/ops/export-records` | 导出留痕 |
| GET | `/admin/board/groups` | 群体列表 |
| POST | `/admin/board/groups` | 建群体 |
| GET | `/admin/board/groups/{id}` | 群体详情（成员） |
| POST | `/admin/board/groups/{id}/members` | 加成员 |
| DELETE | `/admin/board/groups/{id}/members/{userId}` | 移出成员 |
| GET | `/admin/board/groups/{id}/stats` | 匿名聚合统计（≥10 授权） |

> 完整机器可读契约见 `soul-backend/openapi/openapi.json`；本文与 OpenAPI 不一致时，以本 Spec 的模块/流程描述 + OpenAPI 的字段为准，并按 §3.3 B-01 收敛。

## 2.8 配置与环境变量

| 变量 | 默认 | 作用 | 备注 |
|---|---|---|---|
| `SV_MYSQL_HOST/PORT/USER/PASS` | 127.0.0.1/3306/soul_voyage/CHANGE_ME | MySQL | 生产必须显式 |
| `SV_REDIS_HOST/PORT/PASS` | 127.0.0.1/6379/空 | Redis | 生产建议 requirepass |
| `SV_JWT_SECRET` | DEV_ONLY... | JWT 密钥 | ≥32 字节 |
| `SV_MASTER_KEY` | 空 | 信封加密主密钥 | 空则开发临时密钥，重启不可解 |
| `SV_LLM_PROVIDER` | mock | mock/openai | 生产必须显式 |
| `SV_LLM_BASE_URL/API_KEY/MODEL` | 空 | 模型网关 | OpenAI 兼容 |
| `SV_ASR_PROVIDER` | mock | mock/openai | 语音转写 |
| `SV_WX_PROVIDER` | mock | mock/http | 微信登录 |
| `SV_WX_APPID/SECRET` | 空 | 小程序凭证 | 生产必须 |
| `SV_NEO4J_ENABLED` | false | 图实现开关 | 关闭时走 kg_node |
| `SV_NEO4J_URI/USER/PASS/DATABASE` | bolt://127.0.0.1:7687 | 图连接 | 图不可用自动回落 |
| `SV_DISTRIBUTED` | false | 分布式状态开关 | LLM 闸门/滑动窗/事件流/导出快照 |
| `SV_BUSINESS_ZONE` | Asia/Shanghai | 业务时区 | 日/周统计 |
| `SV_CORS_ORIGINS` | localhost:5173,5174 | CORS 白名单 | 生产改为域名 |
| `SV_UPLOAD_MAX_FILE/REQUEST` | 6MB | 上传上限 | 业务侧 5MB 音频 |
| `SV_LLM_RATE_PER_MIN/MAX_IN_FLIGHT/QUEUE_WAIT` | 600/32/1500ms | LLM 闸门 | 0=关闭该层 |

**配置现状问题**：

- `logging.level.com.leyon.soul: DEBUG` 固定，生产日志量/敏感信息风险。
- `SV_MASTER_KEY` 为空时允许临时密钥，缺生产守卫。
- Docker compose 的 `app` 服务没有 healthcheck，但 `web` 使用 `condition: service_healthy`。
- Nginx `client_max_body_size 1m` 与后端 6MB 上传上限不一致。
- 生产入口未配 TLS/安全响应头（M15）。

## 2.9 测试与质量基线

| 层 | 工具 | 现状 | 命令 |
|---|---|---|---|
| 后端单测/集成 | JUnit 5 + H2 + Spring Boot Test | 47 类 / 当前口径 401 例（README 旧段落 151，需修正）；9 例 Neo4j 契约跳过 | `mvn test` |
| 后端覆盖率 | JaCoCo | INSTRUCTION ≥85%、BRANCH ≥75%；README 实测 90.54%/77.19% | `mvn verify` / `mvn jacoco:check` |
| 后端契约 | `OpenApiContractTest` | OpenAPI 快照钉死 | `mvn test -Dtest=OpenApiContractTest` |
| 高风险卡点 | `RiskRulesTest` | 危机句式 100% 召回 | `mvn test -Dtest=RiskRulesTest` |
| 前端单测 | Vitest + happy-dom | 10 个 spec / 100 例；逻辑层 100% 分支 | `npm run coverage` |
| 前端类型/构建 | vue-tsc + Vite | CI 强制 | `npm run build` |
| 前端契约 | openapi-typescript | `gen:api` 后 `git diff --exit-code` | `npm run gen:api` |
| 小程序 | ESLint/Prettier/vue-tsc/build | CI 强制 mp-weixin + H5 | `npm run build:mp-weixin && npm run build:h5` |
| 端到端 | `deploy/tools/smoke_*.mjs`、`walk_*.mjs` | 按里程碑脚本；非 CI 全量 | 手工执行 |
| 压测 | `deploy/tools/load_m9.mjs` | M10/M18 结果留档 | 手工执行 |

**质量缺口**：

- 无自动化浏览器 E2E（Playwright 等）；页面回归依赖人工/脚本截图。
- 小程序无单测。
- 无静态架构约束（如 ArchUnit）防止分层倒退。
- 前端 `schema.d.ts` 未在业务视图中强制使用。

---

# 3. 代码缺陷与重构方案

## 3.1 风险分级与审批规则

| 等级 | 定义 | 审批 | 允许的改动 |
|---|---|---|---|
| **A 安全重构｜低风险可直接优化** | 不改变业务语义、接口字段、数据含义；主要是类型、命名、样式、死代码、配置、测试 | 无需逐项审批，但必须在批次开始前登记 | 结构/样式/类型/日志/配置；不改状态机、判定、加密、数据语义 |
| **B 谨慎重构｜核心业务需我审批** | 触及核心流程、接口契约、数据一致性、安全边界、多节点行为 | 必须逐项书面批准 | 批准后才可改；必须附前后对比与回滚方案 |
| **C 禁止改动模块** | 安全/合规红线，改动即可能造成诊断越界、数据不可解、审计失效、隐私泄露 | **禁止自主修改**；仅可提出方案，等待专项批准 | 默认零改动；只允许新增测试或在外围加适配层 |

**通用约束**：

1. A 类改动不得顺带修改 B/C 类代码；不得以“重构”为名改变 SQL、Prompt、Schema、风险词表、加密格式。
2. B 类改动必须保持“同一输入 → 同一业务结果”；若必须改变结果，需单独列为产品变更，不得混入重构。
3. C 类模块只能在其外部增加适配/包装，不得改内部语义。
4. 每个批次结束必须通过 §3.6 的验收协议。

## 3.2 A 类：安全重构（低风险，可直接优化）

| ID | 位置 | 问题 | 方案 | 风险范围 |
|---|---|---|---|---|
| A-01 | `soul-frontend/src/styles/base.css:15` | `font-size: var(--sv-body)` 引用了不存在的 token；实际回退浏览器 16px，与设计 17px 不一致 | 改为 `--sv-fs-body`；补一个 token 存在性检查（脚本或 stylelint 可选，不引重依赖） | 全部页面字号 1px；无接口/数据影响 |
| A-02 | `App.vue`、`LoginView`、`TodayView`、`MeView`、`PracticeView`、`SvNavBar`、`SvCard` | 5 处 radial-gradient、6 处 linear-gradient、10 处 backdrop-filter、16 处 box-shadow、20–28px 大圆角，构成“塑料感/花哨渐变/冗余装饰” | 按 §5 Token 全面替换：去装饰性渐变/毛玻璃/彩色阴影，改为暖纸层阶 + 1px 线；圆角收敛到 8/6/12px；仅浮层保留一层柔和阴影；不改 DOM 与交互 | 全部页面视觉；无接口/数据；需截图逐页验收 |
| A-03 | `TodayView.vue`、`PracticeView.vue`、`InsightsView.vue`、`ReportDetailView.vue`、`AnalysisView.vue`、`AdminTasksView.vue` | 重复定义 `PlanView/PlanItem/Point/Week`、重复报告渲染类型、管理端 pipeline 选项与后端不一致 | 抽到 `types/domain.ts`、`constants/pipelines.ts`；报告渲染抽为共享 view-model | 2–6 个视图；类型/常量层；无运行时行为变化 |
| A-04 | `SimulationView.vue`(935)、`TodayView.vue`(800)、`PracticeRoomView.vue`(690)、`ReportDetailView.vue`(647)、`ArchiveView.vue`(541) | 巨型单文件组件，状态、模板、样式耦合，难维护/难测试 | 按阶段/区块拆子组件（场景选择/对话/复盘；今日各卡片；跟练各模式；报告各类型）；父组件只做编排 | 5 个页面的组件结构；路由与 API 不变；需逐页交互回归 |
| A-05 | 所有 `views/*.vue` | `.body`、`.t`、`.err`、`.ok`、`.center`、列表行、卡片内边距等重复 scoped CSS | 新增 `PageBody`、`Section`、`EmptyState`、`InlineError`、`MetaText` 等原语；收敛重复样式 | 全部页面视觉；无逻辑/接口 |
| A-06 | `TodayView.vue`、`PracticeView.vue`、`ArchiveView.vue`、`MoodCheckIn` 相关页面 | 使用 `toLocaleDateString('en-CA')` 取浏览器本地日期，与后端 `Asia/Shanghai` 业务日不一致；跨时区会错一天 | 新增 `utils/date.ts`：固定业务时区格式化/解析；所有日期比较走同一工具；测试固定 `TZ=UTC` 与 `TZ=Asia/Shanghai` | 日期显示/查询参数；无 DB 语义变化；需时区测试 |
| A-07 | `soul-frontend/src/api/sse.ts:109` | 终态判断写的是 `CANCELED`（单 L），后端状态是 `CANCELLED`；降级轮询永不识别取消任务，最终误报超时 | 抽 `TASK_STATUS` 常量；补 `CANCELLED`/`EXPIRED`；加单测 | 任务流降级轮询；仅前端；无接口变更 |
| A-08 | `soul-frontend/src/views/admin/AdminTasksView.vue:80-86` | 筛选项包含不存在的 `SUPPORT_PIPELINE`、`REVIEW_PIPELINE`，缺少实际 `COGNITIVE_PIPELINE` | 由后端 `PipelineRegistry` 暴露只读目录（新增管理端只读端点）或共享常量；至少先修正列表 | 管理端筛选；若新增端点则影响 1 个接口；不影响用户端 |
| A-09 | `PracticeView.vue:95`、`DiaryWriteView.vue:186` | 文案硬编码“4 场景”“四个 Agent”，与实际 10 个场景 / 2–4 步流水线不符 | 改为数据驱动或中性文案（“多个场景”“多 Agent 依次处理”） | 文案 only |
| A-10 | `soul-frontend/src/composables/theme.ts:15-19` | `isDark` 依赖 `mode.value` 与一次性 `matchMedia` 读取，系统主题变化时图表不保证重绘 | 使用 `usePreferredDark` 或显式监听 `prefers-color-scheme`；图表 `watch` 重绘 | 主题/图表；无业务 |
| A-11 | `AnalysisView.vue`、`ReportDetailView.vue` 模板 | 模板内 `(s: any)`、隐式 any；类型逃逸 | 定义报告 view-model 类型；仅保留 API 边界的 `Json` | 类型 only |
| A-12 | `common/exception/GlobalExceptionHandler.java` | 未知异常统一映射 `BAD_PARAMS(2001)`，语义错误（500 被当成参数错误） | 新增 `INTERNAL_ERROR(5000)`，未知异常映射 500；参数错误仍 2001；加契约测试 | 错误码契约；前端按非零处理，影响可控 |
| A-13 | `orchestrator/OrchestratorService.java:54` | `Executors.newVirtualThreadPerTaskExecutor()` 作为字段创建，无关闭钩子；优雅停机时任务线程可能悬挂 | 由 Spring 管理 `ExecutorService`，`@PreDestroy` 关闭；保留现有执行语义 | 关停行为；无业务逻辑 |
| A-14 | `OrchestratorService.java:213`、`TaskDtos.java`、`AgentCode.ALL` | `nextConsumer()` 恒返回常量、参数未使用；`@Valid` 导入未用；`AgentCode.ALL` 未使用 | 删除死代码/未用导入；不改变调用链 | 编译层；无行为 |
| A-15 | `application.yml:94` | `com.leyon.soul: DEBUG` 固定；生产日志量与敏感信息风险 | 改为 `${SV_LOG_LEVEL:INFO}`；开发 profile 可 DEBUG；错误日志保留 traceId | 观测/日志量；无业务 |
| A-16 | `auth/SecurityConfig.java:45-54` | `/actuator/**` 除 health 外仅要求 authenticated；普通用户可直接访问 `/actuator/prometheus`、`/actuator/metrics`（若端口可达） | 显式限制 `/actuator/**` 为 ADMIN（health 公开）；Nginx 继续不反代 | 运维访问；无用户业务；需验证普通 token 403 |
| A-17 | `deploy/nginx.conf:25` | `client_max_body_size 1m`，后端允许 6MB、业务音频上限 5MB；>1MB 录音在 Nginx 被 413，M11 语音日记在生产不可用 | 改为 `6m`（或与 `SV_UPLOAD_MAX_FILE` 对齐）；补一次 5MB 上传验证 | 部署配置；语音上传可用性；无代码逻辑 |
| A-18 | `deploy/docker-compose.yml:42-67` | **已满足（2026-11-11 核验）**：`soul-backend/Dockerfile:16-17` 已定义 `HEALTHCHECK` 调用 `/actuator/health`，`service_healthy` 可正常工作 | 无需改动；本项只做核验并在 `docs/baseline/manifest.md` 记录证据 | 部署启动；无应用逻辑 |
| A-19 | `soul-frontend/package.json` | 无 `typecheck` 脚本；`build` 虽跑 vue-tsc，但 CI/本地无法单独卡类型 | 增加 `"typecheck": "vue-tsc -b"`（与 `build` 同一工程引用口径）；CI 增加该步 | 工具链 only |
| A-20 | `soul-frontend/src/views/DiaryListView.vue:61-64` | `more()` 先自增 page 再请求；失败后页码已前进，重试会跳页 | 改为成功后再递增，或请求参数用 `page+1` 且失败回滚 | 日记列表分页；仅前端 |
| A-21 | `soul-frontend/src/views/PracticeView.vue:50-67` | `Promise.all` 任一失败导致整页无数据；计划/练习库/记录相互独立 | 改为 `Promise.allSettled`，分块降级 | 练习首页；仅前端 |
| A-22 | `soul-frontend/src/views/TodayView.vue:108` | `hour` 在 setup 时取一次，页面长时间停留不会更新问候语 | 用 `ref` + 定时/可见性更新，或每次进入刷新 | 文案；仅前端 |

## 3.3 B 类：谨慎重构（核心业务，需逐项审批）

| ID | 位置 | 问题 | 方案 | 风险范围 |
|---|---|---|---|---|
| B-01 | 全部 Controller/Service 的 `Map<String,Object>` 返回；`openapi.json` | 接口字段不可静态描述；OpenAPI 生成类型为宽泛对象；前端手写类型与后端漂移 | 按域定义 `XxxResp/XxxReq` record；Controller 只返回 DTO；OpenAPI 重新生成；字段名保持不变；逐接口 golden diff | **106 个操作/26 个页面**；契约层；高风险，需分批（先读接口后写接口） |
| B-02 | `domain/diary/DiaryService.java:79-104` | 列表先解密全部日记再内存过滤/分页；数据量增长后 O(n) 解密 + 全量加载 | 日期范围下推 SQL 分页；`q` 搜索改为“分页后解密过滤”或引入服务端密文索引（需评估）；明确总数语义 | 日记列表/搜索；影响 1 个接口 + 1 个页面；数据行为需批准 |
| B-03 | `orchestrator/OrchestratorService.java` | 提交、执行、重试、取消、落库、SSE、指标混在一个类；`nextConsumer` 死抽象 | 拆 `TaskSubmissionService` / `TaskExecutionService` / `TaskStepRunner` / `TaskEventPublisher`；状态机显式化；保持事件顺序与状态语义 | **5 条流水线 / 全部任务 SSE / 管理端重跑**；核心，需完整回归 |
| B-04 | `orchestrator/AgentRegistry`、各 Agent `code()`、`TaskDtos.SubmitReq` | Agent 编码是字符串字面量；`JsonNode payload` 无边界校验；未知 pipeline/payload 错误晚发 | 统一 `AgentCode`/`PipelineCode` 枚举；按 pipeline 定义 `SubmitReq` 子类型或校验器；启动时校验注册完整性 | Agent 编排；5 条流水线；高风险 |
| B-05 | `audit/AuditService.java:23-50` | 哈希链仅靠 JVM `synchronized`；多实例并发写会读到同一链尾，产生分叉/断链 | 引入跨节点互斥（Redis `SET NX PX` 或 DB 行锁）；或按节点分段链 + 全局校验；保留现有算法 | 审计合规；多节点部署；高风险 |
| B-06 | `domain/crisis/DailySweeper`、`domain/notify/ReminderScheduler`、`agent/companion/CompanionSweeper` | `@Scheduled` 无分布式锁；多实例会重复巡检/重复提醒/重复封段 | 使用 Redis 锁（轻量，不引 ShedLock）或数据库 leader 选举；单节点行为不变；dedupKey 继续兜底 | 危机生命周期、注销执行、提醒、漫聊封段；高风险 |
| B-07 | `auth/AuthService.java:64-83`、`JwtService`、`soul-frontend/src/api/http.ts`、`sse.ts` | 登录失败计数仅按 username，攻击者可锁死任意账号；Redis 不可用时鉴权抛 500；SSE 无 401 刷新；多标签并发刷新触发 refresh 重用检测，可能全端吊销 | 失败计数改为 `username+IP` 双维度；Redis 异常降级为“放行但记录告警”或明确 503；SSE 复用统一 `authFetch`（单飞刷新 + 跨标签锁）；补测试 | 认证/会话全站；高风险；需安全评审 |
| B-08 | `crypto/MasterKeyProvider.java:27-33` | 未配置 `SV_MASTER_KEY` 时生成临时密钥，生产误用会导致重启后全部密文不可解 | 增加生产 profile 守卫：非 dev/test 且为空则启动失败；开发保留临时密钥 + 告警 | 部署启动；加密可用性；C 类边界，需专项批准 |
| B-09 | `mood_check_in`、`plan_item`、`report`、`user_preferences` 等可变聚合 | 无 `@Version` 乐观锁；多标签/多端并发更新可能丢更新（打卡覆盖、计划勾选、反馈覆盖） | 对可变聚合加 `@Version`；前端冲突提示/重取；保持单请求语义 | 4–6 张表；并发行为；需数据迁移评估 |
| B-10 | `deploy/sql/schema.sql` + `migrate_mN.sql` | 无迁移工具；新环境靠手工导入，升级靠人工执行脚本；易漏 | 引入 Flyway（轻量）建立 baseline，历史脚本归档；schema.sql 保留为初始快照；禁止修改已应用迁移 | 部署/DB；全部模块；需分阶段 |
| B-11 | `orchestrator/api/TaskController.java:27` | 传输层直接依赖 `DiaryService` 并在提交后同步写日记；跨域耦合，失败路径复杂 | 引入 `DiaryTaskFacade`/应用服务，在应用层完成“提交 + 附件落库”；Controller 只做协议 | 日记提交链路；1 个接口 + 1 个页面；需回归 |
| B-12 | `admin/ContentAdminController.java` | KG/场景/练习 upsert 未校验 payload schema；错误内容可进入运行时快照 | 复用 JSON Schema/领域校验；发布前 dry-run；失败不 bump version；保持管理端行为 | 内容/KG/练习；管理端 3 个写接口；高风险 |
| B-13 | `agent/risk/RiskArchiveAgent.java` | 风险事件、危机态、周画像多次写库但类无事务边界；中途失败会留下部分状态 | 在应用层加事务边界（或补偿）；保持“先规则、后语义、再画像”的顺序与幂等 | 风险/危机核心；**C 类邻近，需专项批准** |
| B-14 | `account/AccountService.java:90-106` | 注销执行只匿名化昵称/手机号/openid，`username`、`password_hash` 仍保留；与“注销即遗忘”口径需复核 | 评估是否随机化 username/password_hash；保留审计所需最小关联；需合规确认 | 注销/审计；合规；高风险 |
| B-15 | `soul-frontend/src/api/*`、`views/*` | API 调用散落在视图；`schema.d.ts` 仅 `admin.ts` 使用；领域 endpoint 无统一模块 | 按域建 `api/<domain>.ts`（类型 + 方法）；视图只调用领域 API；生成的 schema 类型作为唯一契约 | 全部页面；纯前端结构；中风险 |
| B-16 | `JwtService`/`AuthService` 的 Redis 调用 | Redis 故障会让所有鉴权请求 500；单点依赖未定义降级 | 定义 Redis 故障策略（本地缓存/短时放行/503）；补故障注入测试 | 全站认证；高风险 |
| B-17 | 上传/导出链路 | 录音仅校验大小，无 MIME/魔数校验；导出快照一次性语义依赖存储实现 | 增加 MIME/魔数校验、文件名安全、下载审计；导出 token 单次消费测试 | 语音日记/导出；2 个接口；中风险 |
| B-18 | 全后端包结构 | 分层混合：Controller/Service/Entity/Repository 同包；Controller 直接依赖 Repository | 按 §3.5 目标结构逐步迁移；先加 ArchUnit 规则（测试依赖，轻量），再迁包 | 全后端；结构层；需分批 |
| B-19 | `ErrorCode` / `GlobalExceptionHandler` | 错误码分段有语义，但缺少统一“内部错误/限流/依赖不可用”分类；部分 HTTP 映射不完整 | 完整错误码表 + 映射表；保留现有码值；新增码只增不改 | 全接口；契约；中风险 |
| B-20 | `miniprogram/` | 无单测；关键登录/绑定/打卡/漫聊逻辑仅靠类型检查与手工走查 | 引入 Vitest（已有依赖生态）覆盖 `api/http`、`stores/auth`、日期/情绪工具；不引入 E2E | 小程序；测试层；低风险但需批准依赖 |

## 3.4 C 类：禁止改动模块（未经专项批准不得修改）

| ID | 模块/文件 | 为什么禁止 | 允许的外部动作 |
|---|---|---|---|
| C-01 | `soul-backend/src/main/java/com/leyon/soul/crypto/**` | 信封加密、DEK 生命周期、密钥销毁是“注销即遗忘”的根基；改错会导致数据永久不可解或隐私泄露 | 只允许新增测试/外部包装；不得改算法、密文格式、密钥版本语义 |
| C-02 | `soul-backend/src/main/java/com/leyon/soul/agent/risk/RiskRules.java` + `resources/risk/rules.json` | 高危句式 100% 召回是安全红线；否定/引述降级与 needsReview 是“宁可多查不可漏查”的核心 | 只允许新增回归样例；不得删词、改阈值、改分级逻辑；词表变更需安全评审 |
| C-03 | `soul-backend/src/main/java/com/leyon/soul/domain/crisis/**` | 危机状态机（NORMAL/CRISIS/COOLING/REVIEW）、7 天窗口、降级判据、append-only 留痕是合规核心 | 只允许外部观察/测试；不得改迁移条件与原因码 |
| C-04 | `soul-backend/src/main/java/com/leyon/soul/llm/OutputValidator.java`、`resources/schemas/*.json`、`llm/PromptTemplates.java` 的免责声明注入 | 诊断词表拦截、闭集 Schema、免责声明注入是产品边界的三层技术落地 | 只允许新增测试/新增 schema；不得放宽词表或删除免责声明 |
| C-05 | `soul-backend/src/main/java/com/leyon/soul/audit/**` 的哈希链算法与字段 | 审计防篡改；算法/字段变更会使历史链失效 | 只允许外部校验/展示；算法变更需专项安全改造 |
| C-06 | `deploy/sql/schema.sql` 既有列语义、`*_enc` 加密标记、状态枚举 | 表结构唯一事实源；改列语义会破坏历史数据/加密兼容 | 只允许新增迁移文件；不得改历史列含义 |
| C-07 | `domain/board/GroupBoardService` 的隐私三闸（密文不出查询、<10 人抑制、贡献者≥3） | 群体看板的隐私承诺；放宽即泄露个体 | 只允许新增测试/展示层；不得降低阈值 |
| C-08 | `account/AccountService` 的注销冷静期/密钥销毁语义 | “注销即遗忘”的合规承诺 | 只允许评估（B-14）；不得直接改销毁时机/范围 |
| C-09 | `wechat/WxBindTicketStore` 的一次性票语义 | 微信绑定凭证；重复使用会造成账号劫持 | 只允许外部观测；不得改 TTL/一次性消费语义 |
| C-10 | `docs/项目手册.md` 的产品边界、免责声明、危机话术；`soul-frontend/src/components/CrisisReferral.vue`、`CrisisView.vue` 的转介资源 | 高危用户兜底路径；文案/资源错误会直接伤害用户 | 只允许排版/可达性优化；不得删改资源与边界说明 |

**C 类变更流程**：提出方案 → 影响分析（安全/合规/数据）→ 用户书面批准 → 单独分支/单独测试 → 双人复核（至少用户 + 自动化证据）。

## 3.5 目标代码结构与包划分

### 3.5.1 后端目标（package-by-feature + 层内分层）

```
com.leyon.soul
├── SoulApplication.java
├── common/                 # 无业务依赖的横切能力
│   ├── api/                # ApiResponse, ErrorCode, PageReq/PageResp
│   ├── error/              # BizException, GlobalExceptionHandler
│   ├── time/               # BusinessCalendar
│   ├── util/               # Ulid, TraceId
│   └── state/              # 限流/分布式状态
├── platform/               # 基础设施适配
│   ├── crypto/             # 现有 crypto（C 类，原样保留）
│   ├── audit/              # 现有 audit（C 类，原样保留）
│   ├── llm/                # LLM 网关/闸门/校验
│   ├── kg/                 # DB/Neo4j 适配
│   ├── asr/                # ASR 适配
│   └── wechat/             # 微信适配
├── auth/
│   ├── api/                # AuthController, WechatAuthController, dto
│   ├── application/        # AuthService, WechatAuthService
│   ├── domain/             # User, RolePermission, repository 接口
│   └── infrastructure/     # JPA 实现, JwtService, JwtAuthFilter, SecurityConfig
├── task/                   # 原 orchestrator
│   ├── api/                # TaskController, dto
│   ├── application/        # 提交/执行/重试/取消
│   ├── domain/             # TaskInstance, StepLog, AgentMessage, 状态机
│   ├── pipeline/           # TaskDefinition, StepSpec, 各 Pipeline
│   └── sse/                # TaskEventBus/EventLog/Relay
├── agent/
│   ├── contract/           # Agent, AgentRuntime, AgentCode, OutputInvalid
│   ├── emotion/
│   ├── trace/
│   ├── support/
│   ├── simulate/
│   ├── risk/               # RiskRules/RiskArchiveAgent（C 类语义）
│   ├── companion/
│   └── letter/
├── diary/                  # api/application/domain/infrastructure
├── emotion/
├── checkin/
├── simulate/
├── companion/
├── report/
├── archive/
├── plan/
├── achievement/
├── notify/
├── crisis/                 # 语义 C 类
├── content/
├── board/
├── account/
└── admin/                  # 瘦 Controller，只调用 application
```

**强制规则**：

1. `api` 层只做协议：参数校验、鉴权注解、DTO 转换；不写业务、不直接依赖 Repository。
2. `application` 层负责用例编排与事务边界；跨域调用只经 application/domain 接口，不跨包直接拿 Repository。
3. `domain` 层不依赖 Spring Web；实体/值对象/领域服务/仓储接口。
4. `infrastructure` 层实现仓储、外部客户端、JWT、过滤器。
5. `common` 不得依赖任何业务包。
6. C 类模块保持现有包路径，避免无意义搬迁；仅在外围加适配。
7. 用 ArchUnit（test scope）固化以上规则；新增依赖前先评估是否“过重”。

### 3.5.2 前端目标（feature-sliced）

```
soul-frontend/src/
├── app/                    # App.vue, main.ts, router
├── design/                 # tokens.css, base.css, primitives（原 components/ui）
├── shared/
│   ├── api/                # http.ts, sse.ts, schema.d.ts, authFetch
│   ├── types/              # 领域类型（由 OpenAPI 生成/收窄）
│   ├── utils/              # date, format, emotions
│   ├── composables/        # theme, voiceNote
│   └── stores/             # ui, auth（跨 feature 的全局态）
├── features/
│   ├── auth/
│   ├── today/
│   ├── diary/
│   ├── insight/
│   ├── practice/
│   ├── simulation/
│   ├── companion/
│   ├── archive/
│   ├── growth/             # letters, achievements, notifications
│   ├── account/
│   └── admin/
└── views/                  # 只保留薄路由包装（可选）
```

**强制规则**：

1. 视图文件 ≤ 200 行；单组件 ≤ 300 行；超出即拆。
2. 每个 feature 自带 `api.ts`、`components/`、`stores/`（如有）、`views/`。
3. 禁止跨 feature 直接 import 内部组件；共享能力放 `shared`。
4. 所有颜色/间距/字号/圆角必须来自 `design/tokens.css`；禁止硬编码色值（动态图表颜色也走 CSS 变量）。
5. 禁止在模板写 `any`；API 边界统一用 `Json`/生成的 schema 类型。
6. 每个异步页面必须有 loading / empty / error 三态；请求可 abort。
7. 不使用 emoji 作为 UI 图标或唯一语义载体。
8. 统一 `authFetch` 处理 401 刷新、SSE 鉴权、跨标签刷新锁。

## 3.6 重构批次与验收协议

### 批次划分（严格顺序）

| 批次 | 内容 | 前置 | 出口 |
|---|---|---|---|
| **Batch 0** | 基线快照与护栏：保存 OpenAPI、schema.d.ts、DB 结构、关键接口 golden JSON、4 个宽度截图、测试报告；登记 C 类文件清单 | 无 | 快照归档；后续每批 diff 对照 |
| **Batch 1** | A 类低风险：A-01、A-03、A-05、A-07~A-22（不含大视觉改版）；部署配置修复 | Batch 0 | 全量测试绿；接口字段无 diff；截图仅预期变化 |
| **Batch 2** | UI 设计系统落地：§5 Token + 壳 + 页面逐页（纯展示；不改业务） | Batch 1 | 0 装饰性渐变/毛玻璃/高光/彩色阴影/emoji 图标；仅浮层保留一层柔和阴影；全页面截图；a11y 清单 |
| **Batch 3** | B 类契约：B-01、B-02、B-15、B-19（先读接口后写接口，逐域） | Batch 1 | OpenAPI/生成类型/golden diff 全部审阅通过 |
| **Batch 4** | B 类核心：B-03、B-04、B-11、B-13（编排/风险邻近） | Batch 3 | 5 条流水线回归；SSE 事件序列一致；风险结果一致 |
| **Batch 5** | B 类安全/基础设施：B-05~B-10、B-16~B-18、B-20 | Batch 4 | 多节点/故障注入/迁移演练通过 |
| **Batch 6** | 扩展功能（§4），每项单独变更单 | Batch 2–5 完成 | 各项验收 + 回归 |

> 批次可并行的前提：不共享文件、不共享契约、不共享数据表；否则串行。

### 行为等价验收（每批必做）

1. **后端**：`mvn test` 全绿；`mvn verify` 覆盖率不低于基线；`RiskRulesTest` 必绿。
2. **前端**：`npm run lint`、`format:check`、`test`、`coverage`、`build` 全绿；`gen:api` 后 `git diff --exit-code`。
3. **小程序**：`lint`、`format:check`、`typecheck`、`build:mp-weixin`、`build:h5` 全绿。
4. **接口 golden diff**：对 12 个关键场景保存请求/响应：
   - 注册/登录/刷新/登出
   - 日记提交 + 任务流
   - 高风险日记（CRISIS）
   - 模拟结束复盘
   - 漫聊收段消化
   - 打卡/补签
   - 计划生成/跟练
   - 报告详情/反馈
   - 档案导出
   - 注销/撤回
   - 管理端任务重跑
   - 群体看板抑制
   仅允许批准字段变化；状态码/错误码/业务字段不得漂移。
5. **数据库**：迁移前后行数、状态分布、关键唯一键一致；加密字段可解密（用测试密钥）。
6. **UI**：4 个宽度（375/768/1024/1440）× 明暗两色截图；无布局溢出；交互路径可完成。
7. **禁止项**：`git diff` 中不得出现 C 类文件（除非专项批准单）。

### 回滚方案

- 每批独立分支/提交；数据库迁移必须可前滚（禁止不可逆 DDL，除非有备份）。
- 前端 UI 改版通过 feature flag 或分支切换；保留旧 token 文件直到验收通过。
- 后端契约变更保留旧 DTO 适配期（一个版本），确认无调用方后再删。

---

# 4. 扩展功能规划

## 4.1 规划原则

1. **贴合现有架构**：优先复用 Orchestrator / Agent / SSE / 内容快照 / 通知 / 计划，不引入第二套任务引擎。
2. **不引入过重依赖**：优先用现有 Spring Boot / Redis / MySQL / ECharts / Vue 能力；新增依赖必须是“轻量、可替换、测试友好”。
3. **安全与隐私优先**：任何扩展不得放宽 C 类红线；涉及群体数据必须先过隐私三闸。
4. **可观测**：新功能必须有指标/日志/失败路径，不做“黑盒”。
5. **可回滚**：新功能默认开关（配置或权限），异常时可关闭。

## 4.2 高优（P0/P1）

| ID | 功能 | 价值 | 依赖 | 成本 | 风险/边界 |
|---|---|---|---|---|---|
| H-01 | **任务中心 2.0**：统一任务历史、失败原因、步骤时间线、断点重放、失败重试入口 | 用户对“系统为我做了什么”有掌控感；降低支持成本 | `/tasks`、SSE、`AdminTask` 重跑；B-03 | M | 不改任务语义；仅展示/重试入口 |
| H-02 | **情绪洞察 2.0**：周报自动摘要、波动提示、压力源/误区解释、可执行的下一步 | 提升留存与自助有效性 | `emotion_profile`、`emotion_trajectory`、`RiskRules`（只读） | M | 先用确定性规则；LLM 摘要可选；不得输出诊断 |
| H-03 | **成长计划自适应**：按完成率/情绪变化顺延、替换、减量；支持“今天不做” | 降低挫败感，提升完成率 | `PlanService`、`plan_item`、`exercise_record` | M | 只改计划编排；不改风险/危机逻辑 |
| H-04 | **内容运营闭环**：KG/场景/练习草稿 → 预览 → 发布 → 回滚；发布前 schema 校验 | 内容可运营、可回滚 | `ContentAdmin`、`ContentStore`、`kg_node`、Neo4j 同步 | L | 触及内容真源；需 B-12 先行；C 类词表不动 |
| H-05 | **可观测与成本护栏**：任务失败告警、LLM 成本预算、内容质量抽检、慢请求 TopN | 生产可运营 | `AdminMetrics`、`Notification`、Micrometer | S–M | 只读聚合；不新增外部依赖 |
| H-06 | **无障碍与键盘操作**：焦点管理、skip link、键盘快捷键、屏幕阅读器标签、对比度 | 覆盖更多用户；符合安静阅读体验 | §5 UI 系统 | M | 纯前端；不改业务 |
| H-07 | **M15 上线收口**：真实 LLM/ASR 密钥、HTTPS/域名、小程序线上域名、Neo4j compose、生产安全头 | 可上线 | `deploy/`、`nginx.conf`、`docker-compose.yml` | M | 运维/合规；不属于功能开发，但优先级最高 |

## 4.3 中优（P2）

| ID | 功能 | 价值 | 依赖 | 成本 |
|---|---|---|---|---|
| M-01 | **语音日记 2.0**：转写校对、分段、离线草稿队列、录音波形/时长可视化 | 降低表达门槛 | `AsrClient`、`voiceNote`、`draft` | M |
| M-02 | **知识图谱可视化**：只读图谱浏览、节点关联、误区→练习→场景路径 | 让“溯源”可解释 | `Neo4jKgService`/`DbKgService`、ECharts graph | M |
| M-03 | **报告导出 2.0**：章节化 PDF、打印模板、单报告导出 | 档案可带走 | 现有 `ArchiveService`/print | S–M |
| M-04 | **会话与设备管理**：登录设备列表、单设备下线、异常登录提示 | 账号安全 | `JwtService`/Redis；B-07 | M |
| M-05 | **多端一致性**：Web/小程序草稿、偏好、任务状态同步；冲突提示 | 双端体验 | `draft`、`user_preferences`、`/tasks` | M |
| M-06 | **管理端批量操作与审计检索**：批量冻结/导出、审计动作筛选、导出 CSV | 运营效率 | `AdminSupport`/`AdminOps` | M |
| M-07 | **推荐解释性**：为什么推荐这个练习/场景（引用压力源/误区/历史完成） | 提升信任 | KG、`recommendedFor`、`SupportAgent` | S–M |

## 4.4 备选（P3，明确暂缓）

| ID | 功能 | 暂缓理由 | 依赖 | 成本 |
|---|---|---|---|---|
| O-01 | RAG 向量检索 | 需要 embedding + 向量库，依赖重；当前 KG + 闭集 + 规则已够 | 新向量库/模型 | L |
| O-02 | 实时语音对话 / 多人模拟 | 需要流式 ASR/TTS/WebRTC，合规与成本高 | 新实时链路 | L |
| O-03 | 社区/同伴支持 | 内容审核、危机扩散、隐私风险极高 | 审核体系 | L |
| O-04 | 插件化 Agent 市场 | 过度设计，当前 Agent 数量与团队规模不需要 | 插件协议 | L |
| O-05 | 数据大屏/复杂 BI | 现有管理端指标已覆盖；价值低 | 无 | M |
| O-06 | 原生移动 App | 小程序 + PWA 已覆盖；维护成本高 | 无 | L |

## 4.5 依赖与成本汇总

- **S（小）**：≤10 个文件、单模块、无表结构/契约变更；可在 1 个批次内完成。
- **M（中）**：1–3 个模块、可能新增只读接口/前端页面；需要回归但不改核心语义。
- **L（大）**：跨模块、涉及表结构/契约/核心编排；必须拆批次、灰度、可回滚。

**新增依赖白名单（轻量）**：

- 测试：ArchUnit（后端架构规则）、Vitest（小程序复用现有生态）。
- 迁移：Flyway（替代手工 SQL）。
- 不引入：LangChain、向量数据库、WebRTC 框架、消息队列（现有 Redis 足够）、第二套状态管理。

---

# 5. UI 设计规范（静室手记 / Quiet Journal）

## 5.1 设计定位：一本放在桌边的日记

心屿漫行不是效率工具，也不是社交产品。用户在这里写下的是情绪、关系和自我怀疑，界面首先要传达的是**安全、私密、被接住**，其次才是效率。

设计隐喻：**暖纸、墨色、夜灯、一页手记**。

关键词：**安静、温暖、私密、克制、可读**。

三条底线：

1. **不塑料**：不用高光、玻璃拟态、糖果渐变、彩色投影、超大圆角、彩色图标底。
2. **不喧哗**：不用高饱和色块、高频动效、弹跳、视差、装饰性插画堆叠。
3. **不侵入**：内容优先，控件退后；页面像一页可以慢慢读完的纸，而不是仪表盘。

与旧 iOS 风的关系：保留其触控尺寸、安全区、无障碍与组件完整度；替换其“果冻感/毛玻璃/大圆角/彩色渐变”的视觉语言。

与常见的极客/终端风不同：极客风强调终端、等宽、网格、冷色；本项目应强调**纸感、衬线阅读、暖色、留白、夜读**。等宽字体只用于管理端 JSON/编号等极少数场景，不作为用户端主视觉。

## 5.2 硬规则（全站，无例外）

1. **禁止装饰性渐变**：禁止 `linear-gradient`、`radial-gradient`、`conic-gradient` 作为背景/按钮/图标底；图表面积也不使用渐变。
2. **禁止毛玻璃与高光**：禁止 `backdrop-filter`、玻璃高光、镜面反射、半透明模糊导航。
3. **允许纸张层阶，禁止装饰性阴影**：页面用 `bg / card / card2` 三层暖纸色 + 1px 边框建立层次；只有 Dialog / Sheet / Toast 可使用一层柔和阴影 `--sv-elev-1`。
4. **圆角克制**：卡片 8px、输入/按钮 6px、弹层 12px、标签 3px；除状态点、进度环外不做胶囊形。
5. **单一强调色**：只允许一个强调色「苔绿」；成功/警告/危险色仅用于状态与危险操作，不做大面积装饰。
6. **正文用衬线，界面用无衬线**：日记、报告、来信等长文用 `--sv-serif`；导航、按钮、表单用 `--sv-font`；数字用 tabular 无衬线（不强制等宽）。
7. **1px 线与 4/8 栅格**：所有分隔、卡片边框、表格行线统一 1px；间距/尺寸为 4 的倍数。
8. **动效轻而短**：180–240ms，只做透明度与 6px 以内位移；禁止弹跳/缩放/视差。呼吸练习的功能性动画例外，但必须提供 reduced-motion 的文字倒计时替代。
9. **无 emoji 图标**：UI 图标、情绪、勋章、信封、收藏等一律用线性图标或文字/色点；用户自己输入的内容中可出现 emoji，但系统不得用 emoji 作为唯一语义载体。
10. **每个视觉元素都要有信息**：日期戳、页码、书签、分隔线可以保留；光斑、装饰插画、无意义色块一律删除。
11. **一屏只讲一件事**：用户端首屏不超过 2 个主区块；管理端除外，但也要保持表格的安静。
12. **可读性优先**：正文对比度 ≥4.5:1；长文行高 ≥1.8；阅读列 ≤68ch；不使用 10px 以下正文。

## 5.3 Design Tokens

> 在 `soul-frontend/src/styles/tokens.css` 落地；小程序 `styles/tokens.scss` 同步。
> 迁移期保留现有 `--sv-*` 变量名，只替换取值；新增 `--sv-accent` 作为强调色 canonical 名，`--sv-indigo` 作为迁移别名。

### 5.3.1 颜色（Light · 暖纸）

| Token | 值 | 用途 |
|---|---|---|
| `--sv-bg` | `#F3EEE4` | 页面底色（暖纸） |
| `--sv-card` | `#FBF8F1` | 卡片/纸张 |
| `--sv-card2` | `#EDE6D9` | 次级纸张、表头、输入底 |
| `--sv-label` | `#2B2722` | 主文字（墨色） |
| `--sv-label2` | `#5F584D` | 次文字 |
| `--sv-label3` | `#6F6659` | 元信息（保证 AA） |
| `--sv-sep` | `#DDD3C2` | 1px 分隔/边框 |
| `--sv-line-strong` | `#C9BCA6` | 输入/强调边框 |
| `--sv-accent` | `#3F5F4E` | 强调色（苔绿） |
| `--sv-accent-soft` | `rgba(63,95,78,.10)` | 选中底/标签底 |
| `--sv-on-accent` | `#FBF8F1` | 强调色上的文字 |
| `--sv-ok` | `#3F6B4F` | 成功/平稳 |
| `--sv-warn` | `#9A6A24` | 需要关照 |
| `--sv-danger` | `#A3483C` | 危险/删除/高危 |
| `--sv-focus` | `#3F5F4E` | 焦点环 |
| `--sv-elev-1` | `0 10px 30px rgba(43,39,34,.10)` | 仅 Dialog/Sheet/Toast |
| `--sv-fill2` | `#E7DFD1` | 次级填充 |
| `--sv-fill3` | `#EFE9DE` | 弱填充 |

### 5.3.2 颜色（Dark · 夜读）

| Token | 值 | 用途 |
|---|---|---|
| `--sv-bg` | `#141519` | 页面底色（夜色） |
| `--sv-card` | `#1C1E23` | 卡片/纸张 |
| `--sv-card2` | `#242730` | 次级纸张/表头 |
| `--sv-label` | `#EDE7DC` | 主文字 |
| `--sv-label2` | `#B7B0A3` | 次文字 |
| `--sv-label3` | `#8E8779` | 元信息 |
| `--sv-sep` | `#2E323A` | 1px 分隔 |
| `--sv-line-strong` | `#414652` | 输入/强调边框 |
| `--sv-accent` | `#9DBFA9` | 强调色（月下苔绿） |
| `--sv-accent-soft` | `rgba(157,191,169,.14)` | 选中底 |
| `--sv-on-accent` | `#141519` | 强调色上的文字 |
| `--sv-ok` | `#8FC3A1` | 成功 |
| `--sv-warn` | `#D8B070` | 警告 |
| `--sv-danger` | `#E09186` | 危险 |
| `--sv-focus` | `#9DBFA9` | 焦点环 |
| `--sv-elev-1` | `0 12px 32px rgba(0,0,0,.45)` | 浮层 |
| `--sv-fill2` | `#242730` | 次级填充 |
| `--sv-fill3` | `#1C1E23` | 弱填充 |

### 5.3.3 情绪色（16，低饱和水彩感）

> 只用于 4px 圆点、1px 线、标签左边框、图表数据系列；禁止大面积背景。

| 情绪 | 值 | 情绪 | 值 |
|---|---|---|---|
| joy | `#C9A227` | calm | `#6F8F7A` |
| anxiety | `#C08A3E` | anger | `#A95245` |
| sad | `#5E6E9E` | numb | `#8A857C` |
| shame | `#A85E72` | fear | `#7C6A9E` |
| lonely | `#5F8399` | gratitude | `#A97C4F` |
| confusion | `#8B8578` | tired | `#6E7478` |
| expectation | `#5D8A8A` | bored | `#8E8B80` |
| grief | `#7A6A55` | pressure | `#A85E5E` |

### 5.3.4 字体

| Token | 值 | 用途 |
|---|---|---|
| `--sv-font` | `"Source Han Sans SC","Noto Sans SC","PingFang SC","Microsoft YaHei",system-ui,sans-serif` | 界面/导航/按钮/表单 |
| `--sv-serif` | `"Source Han Serif SC","Noto Serif SC","Songti SC","SimSun",serif` | 日记/报告/来信正文 |
| `--sv-num` | `"Inter","PingFang SC",system-ui,sans-serif` | 数字/日期/统计（tabular-nums） |

### 5.3.5 字阶

| Token | 值 | 用途 |
|---|---|---|
| `--sv-fs-largename` | 30px | 首页日期/大标题（衬线可选） |
| `--sv-fs-title1` | 24px | 页面标题 |
| `--sv-fs-title2` | 20px | 区块标题 |
| `--sv-fs-title3` | 18px | 卡片标题 |
| `--sv-fs-headline` | 16px | 重点正文/按钮 |
| `--sv-fs-body` | 14px | 界面正文 |
| `--sv-fs-prose` | 17px | 日记/报告/来信正文（衬线） |
| `--sv-fs-callout` | 15px | 引导语 |
| `--sv-fs-subhead` | 13px | 次要正文 |
| `--sv-fs-footnote` | 12px | 辅助说明 |
| `--sv-fs-caption1` | 11px | 元信息/标签 |
| `--sv-fs-caption2` | 10px | 极小标注（仅非正文） |
| `--sv-lh-tight` | 1.3 | 标题 |
| `--sv-lh-body` | 1.65 | 界面正文 |
| `--sv-lh-prose` | 1.85 | 长文阅读 |

### 5.3.6 间距/圆角/边框

| Token | 值 | 用途 |
|---|---|---|
| `--sv-s1` | 4px | 极小间隙 |
| `--sv-s2` | 8px | 图标与文字 |
| `--sv-s3` | 12px | 行内间距 |
| `--sv-s4` | 16px | 紧凑内边距 |
| `--sv-s5` | 20px | 卡片内边距（默认） |
| `--sv-s6` | 24px | 区块间距 |
| `--sv-s7` | 32px | 大区块 |
| `--sv-s8` | 48px | 页面级留白 |
| `--sv-s9` | 64px | 首屏留白 |
| `--sv-r-card` | 8px | 卡片/纸张 |
| `--sv-r-ctl` | 6px | 按钮/输入 |
| `--sv-r-sheet` | 12px | Dialog/Sheet |
| `--sv-r-tag` | 3px | 标签 |
| `--sv-border` | `1px solid var(--sv-sep)` | 全部边框 |

## 5.4 排印规则

1. **长文用衬线**：日记正文、报告正文、成长来信使用 `--sv-serif`，17px/1.85，阅读列 ≤68ch。
2. **界面用无衬线**：导航、按钮、表单、列表、表格使用 `--sv-font`，14px 为默认。
3. **数字用 tabular**：时间、分数、统计、金额使用 `--sv-num` + `font-variant-numeric: tabular-nums`；不强制全站等宽。
4. **标题克制**：中文标题 500–600 字重；页面标题 24px；区块标题 18–20px；不使用 700+ 的厚重标题。
5. **日期戳**：日记/档案/来信使用衬线小号日期（如 `2026.11.11`），作为“手记”的锚点；不要用全大写英文。
6. **标签**：11px，字距 0；仅管理端 JSON/任务号等使用等宽字体。

## 5.5 布局与间距

```
移动（<768px）
┌──────────────────────────┐
│ Header 52px（1px 底边）   │
├──────────────────────────┤
│ Content  padding 20px     │
│ max-width 720px           │
│（暖纸底，滚动区）         │
├──────────────────────────┤
│ TabBar 60px（1px 顶边）   │
└──────────────────────────┘

桌面（≥1024px）
┌────────────────────────────────────────────┐
│  背景：暖纸/夜色（纯色，无光晕）             │
│        ┌──────────────────────────┐        │
│        │  纸页列 max 760px         │        │
│        │  1px 边框 · 8px 圆角      │        │
│        │  padding 32px             │        │
│        └──────────────────────────┘        │
└────────────────────────────────────────────┘

管理端（≥1024px）
┌─────────┬───────────────────────────────────┐
│ Rail    │ Content fluid max 1200px          │
│ 200px   │ 表格/筛选/批量操作                 │
│ 1px 右边│ 同一暖纸体系，密度更高             │
└─────────┴───────────────────────────────────┘
```

- 移动端页面左右 20px；桌面纸页列 32px；最大阅读宽度 720px。
- 卡片之间 12–16px；区块之间 24–32px。
- 桌面不做“手机壳”：不模拟 430px 手机边框、不做 44px 大圆角、不加光晕背景。
- 桌面背景使用纯色 `--sv-bg`；纸页列可用 `--sv-card` + 1px 边框，形成“桌上放着一页纸”的感觉。
- 管理端沿用同一暖纸体系，但允许表格与更高信息密度；不做冷色仪表盘。

## 5.6 组件规范

| 组件 | 规范 |
|---|---|
| **Button** | 高度 40px（sm 32px）；圆角 6px；字号 14/13；主按钮 `accent` 填充 + `on-accent`；次按钮纸面 + 1px 边框；危险按钮仅删除/注销/危机操作；禁用 opacity .45；按下只改背景，不缩放 |
| **Input/Textarea** | 高度 40px（textarea 自适应）；圆角 6px；`card2` 底 + 1px `line-strong`；focus 1px `accent` + 2px `accent-soft` 外环；错误 1px `danger` + 12px 文案；placeholder `label3` |
| **Card** | `card` 底 + 1px 边框 + 8px 圆角；padding 20px；默认无阴影；卡片内不再嵌套卡片（用 1px 分隔线） |
| **List Row** | 高度 52–60px；1px 底边；左侧线性图标 20px；标题 14px；副文案 12px `label2`；右侧 chevron 14px；不使用圆角卡片包裹列表 |
| **Table** | 仅管理端/档案统计；表头 11px `label3`；行 44px；1px 行线；数字右对齐 tabular；无斑马纹；无外阴影；空态一行文字 |
| **Tag/Chip** | 高度 24px；3px 圆角；11px；1px 边框；选中 `accent-soft` + `accent` 文字；状态色只用于状态标签 |
| **Dialog** | 12px 圆角；`card` 底 + 1px 边框 + `elev-1`；最大宽 400px；标题 18/600；按钮右对齐；危险动作二次确认 |
| **Sheet** | 底部抽屉；顶部 16px 圆角；`card` 底 + `elev-1`；遮罩 `rgba(20,17,12,.45)`；最多 80dvh；不做毛玻璃 |
| **Toast** | 底部居中；`card` + 1px 边框 + `elev-1`；13px；3s 自动消失；不遮挡 TabBar |
| **NavBar** | 高度 52px；1px 底边；标题 16/600；返回按钮 44×44；粘性定位但背景纯色；首页可用衬线日期标题 |
| **TabBar** | 高度 60px；1px 顶边；图标 22px；标签 11px；激活 `accent`；非激活 `label3`；不漂浮、不做胶囊 |
| **Empty** | 一句平静说明 + 可选主按钮；不使用插画/emoji/大面积虚线框 |
| **Skeleton** | 暖纸色块 + 1.4s 透明度脉冲；禁止 shimmer 渐变 |
| **Disclaimer** | 1px 顶边 + 12px `label2`；不使用彩色卡片；固定展示“不构成医学诊断” |
| **Emotion Dial** | 16 个线性表情（SVG，非 emoji）或 4px 色点 + 11px 标签；选中 1px accent 外环；必须可键盘操作；颜色不是唯一信息载体 |
| **CrisisReferral** | 高对比、无装饰；110/120 与热线号码 20px 衬线或 tabular；不使用渐变/插画；危机页保持最高可读性 |
| **Divider** | 1px `sep`；章节之间可用“日期 + 1px 线”的手记式分隔 |

## 5.7 图表规范

- 背景透明；坐标轴线 1px `sep`；网格线 1px 虚线（`sep` 40% 透明）。
- 折线 1.5px；数据点 3px；禁止面积渐变（可用 8% 单色平铺）。
- 柱状图宽度 ≤10px；圆角 0；颜色仅 accent / label3 / 语义色。
- 坐标标签 11px `label3` tabular；图例 11px。
- Tooltip：`card` + 1px 边框 + 4px 圆角；无阴影；数字 tabular。
- 用户端首屏最多 2 个核心图表（情绪曲线 + 压力源）；其余折叠到“更多洞察”。
- 暗色主题重绘必须响应系统主题变化。
- 图表旁必须有一句文字摘要，避免“只有图没有结论”。

## 5.8 动效与交互

- 页面切换：200ms 透明度 + 6px 上移；不做 24% 大位移、不做缩放。
- 弹层：180ms 淡入 + 8px 上移；不做弹簧。
- 按钮：按下改背景/边框；禁止 scale。
- 加载：纸质 skeleton 或 1.4s opacity 脉冲；禁止多色旋转 spinner。
- 呼吸练习：允许功能性缩放/呼吸环动画；`prefers-reduced-motion` 时改为数字倒计时 + 文字提示。
- 重要操作（删除/注销/导出）必须有确认；危险按钮不得与主按钮同色。
- 悬停仅在桌面端使用：`card2` 底或 1px 边框加深，不做位移。

## 5.9 文案与无障碍

**文案语气**：温暖、平静、成人化，像一位不过度热情的朋友；不卖萌、不说教、不制造焦虑。

| 现状（示例） | 目标 |
|---|---|
| “登岛成功，从这里开始漫行” | “已登录” |
| “树洞正在打开…” | “正在建立会话…” |
| “心屿正在轻轻读你写的字…” | “正在分析这段文字…” |
| “做完 4 轮啦，打卡” | “完成 4 轮 · 记录” |
| “都是好消息” | “暂无未读” |
| “今天也要好好陪自己” | “今天想从哪里开始？” |
| “断签不羞辱” | “漏掉一天也没关系，可以补记” |
| “它如何提醒你” | “提醒方式” |

**无障碍**：

- 正文对比度 ≥4.5:1；大字号 ≥3:1；`--sv-label3` 不得低于 AA。
- 焦点可见：1px accent + 2px `accent-soft` 外环；键盘可完成所有主流程。
- 触控目标：移动 ≥44×44px；桌面 ≥32×32px。
- 图标必须有 `aria-label` 或伴随文字；emoji 不得作为唯一语义。
- 表单必须有 `<label>`；错误用 `role="alert"`；异步状态用 `aria-live="polite"`。
- 图表提供文字摘要/`aria-label`；颜色不是唯一信息载体（同时给数值/标签）。
- 尊重 `prefers-reduced-motion`、`prefers-color-scheme`。
- 长文（日记/报告/来信）保证 17px/1.85 与 68ch 阅读列；避免纯灰浅字。

## 5.10 页面优化清单（按页面顺序）

> 优先级：P0 = 首屏/主链路；P1 = 核心功能页；P2 = 次级页；P3 = 管理端/边缘页。
> 共同项：去装饰性渐变/毛玻璃/高光/彩色阴影/emoji；暖纸 Token；1px 线；长文衬线；loading/empty/error 三态；键盘可达。

| # | 页面 | 现状问题 | 改造要点 | 优先级 |
|---|---|---|---|---|
| 0 | **全局壳** `App.vue` / `SvNavBar` / `SvTabBar` / `SvCard` / `base.css` | 桌面 430px 手机壳 + 44px 圆角 + 大阴影；`stage::before` 三色 radial 光晕 + blur 60px；导航毛玻璃；卡片 20px 圆角 + 阴影 | 去光晕/手机壳；桌面改“暖纸背景 + 760px 纸页列”；Nav 52px 纯色 1px 底边；Tab 60px 1px 顶边；Card 8px + 1px 边框；修复 `--sv-body` | P0 |
| 1 | **登录** `/login` | 渐变 logo + 阴影卡片 + 大圆角；品牌文案偏“登岛” | 字标 + 一页暖纸表单；主按钮 40px；错误 inline；回车提交；文案改“登录/注册”；保留危机入口；去掉渐变 logo | P0 |
| 2 | **今日** `/today` | 8 个卡片堆叠、渐变“来漫聊”、emoji（🔥🌿🌱）、彩色图标底、信息密度低 | 首屏：衬线日期 + 一句问候 → 心情打卡 → 今日计划 → 双入口（两条纸面行）；最近动态/每日一读折叠；emoji 换线性图标；卡片降为 4–5 个 | P0 |
| 3 | **日记列表** `/diaries` | 卡片 + 阴影 + 圆角；每条占高；搜索框突兀；空态含 emoji ✎ | 改为手记式时间线：日期戳 + 标题/预览 2 行 + 情绪色点 + 语音标记；1px 分隔；搜索 40px 输入；删除保留滑动 + 详情显式按钮 | P1 |
| 4 | **写日记** `/diaries/write` | 9 行 textarea；emoji 心情；语音按钮大；提示“四个 Agent”不准确 | 正文衬线 17px/1.9；心情改 5 个线性表情/文字标签（1–5）；语音按钮 40px 线性图标；步骤提示中性化；底部粘性提交；保留自动草稿 | P0 |
| 5 | **分析页** `/diaries/analysis` | 卡片 + emoji（🌿🤔）；步骤时间线与报告混排；长文无折叠 | 顶部固定步骤条（情绪识别/溯源/疏导/风险归档）；报告分区 + 折叠；情绪条 1px 轨道；去 emoji；免责声明固定底部；失败态明确 | P0 |
| 6 | **日记详情** `/diaries/:id` | 与列表重复的卡片风格；元信息弱；报告入口不清晰 | 日期戳 + 衬线正文；关联报告用 1px 索引行；编辑/删除/重分析放底部操作区；stale 提示 1px 警告条 | P1 |
| 7 | **洞察** `/insights` | 4 个图表同屏；图表阴影/渐变/圆角；周画像卡片堆叠 | 首屏只留情绪曲线 + 压力源；误区趋势/雷达折叠；图表按 §5.7；周画像改安静表格/文字摘要；范围切换用文字分段；空态直接说明 | P1 |
| 8 | **练习** `/practice` | 渐变 hero；计划/练习库/足迹三卡片；emoji 🌿；场景数文案错误 | Hero 改纸面横幅 + 线性图标；计划改 checklist 行；练习库 2 列纸面卡片（移动 1 列）；足迹简洁列表；动态数量；去 emoji | P1 |
| 9 | **模拟训练** `/practice/sim` | 935 行单组件；场景卡大圆角；聊天气泡阴影；复盘报告长 | 拆 ScenePicker/ChatStage/ReviewStage；场景卡 1px 边框 + 标签；气泡纸面 1px 边框；紧张度 1px meter；复盘用分区/折叠；grade 用 3px 方标签 | P1 |
| 10 | **跟练房间** `/practice/room/:id` | 呼吸环光晕/缩放；54321 文案冗长；CBT 表单卡片化；emoji 🌿 | 呼吸环改 1px 圆环 + 中心衬线倒计时；保留节奏但去光晕；54321 分步紧凑；CBT 用纸面分区；完成态文字化 | P1 |
| 11 | **漫聊** `/companion` | 气泡阴影/圆角；问候语长；输入区与结束按钮拥挤 | 气泡纸面 1px 边框 + 8px 圆角；问候语一行；输入栏固定底部；剩余句数小字；“这句别分析”文字按钮；收段确认 | P1 |
| 12 | **成长档案** `/archive` | 541 行；卡片 + 表格 + 列表混排；emoji 💡🌿；导出按钮弱 | 拆 ArchiveSummary/ReportIndex/EmotionTable/TrainingTable/Favorites；报告索引用纸面行；导出主按钮置顶；打印页黑白可读 | P2 |
| 13 | **报告详情** `/archive/report/:id` | 647 行；与 Analysis 重复渲染；emoji；反馈区卡片化 | 抽 `ReportRenderer`（TRACE/SUPPORT/SIMULATE/未知）；分区折叠；正文衬线；反馈用文字分段按钮；收藏文字化；免责声明固定 | P2 |
| 14 | **成长来信** `/letters` | 信封 emoji ✉️；卡片堆叠；正文无阅读节奏 | `WEEK 38` 日期戳 + 信件标题；正文衬线 17/1.9；引用块 1px 左边框；无 emoji；空态一句 | P2 |
| 15 | **成就墙** `/achievements` | 🏅/🔒 emoji；卡片网格装饰重 | 做成“书签/印记”网格：1px 边框 + 日期戳 + 简短说明；已解锁用 accent 左边框；未解锁仅文字“尚未点亮”；无 emoji | P2 |
| 16 | **通知** `/notifications` | 文案“都是好消息”；卡片内 row；未读点弱 | 标题“通知”；分组标题 11px；未读 4px accent 点；行高 60px；全部已读文字按钮；空态平静 | P2 |
| 17 | **我的** `/me` | 渐变头像；彩色图标底；列表分组多；emoji | 方形字母头像（1px 边框，6px 圆角）；分组标题 11px；设置行 56px；开关扁平；去掉彩色图标底 | P1 |
| 18 | **隐私中心** `/account` | 表单卡片化；危险区与普通区区分弱 | 纸面分区；危险区 1px danger 左边框；二次确认；导出/改密/注销按风险排序；说明 12px | P2 |
| 19 | **危机页** `/crisis` | 卡片 + 圆角 + 图标底；电话号码不够突出 | 高对比纯文本；110/120 置顶 24px；热线行 1px 分隔；无装饰；保留边界说明；夜间模式同样高对比 | P0 |
| 20 | **管理端总览** `/admin` | 移动端胶囊 Tab；统计卡圆角；图表样式花 | 左侧 Rail 导航；统计用纸面数字块/表格；Token 图柱状 1px；表格安静；无阴影 | P2 |
| 21 | **管理端任务** `/admin/tasks` | 筛选选项错误；卡片列表；步骤弹层卡片化 | 修正 pipeline 列表；表格化任务；状态 3px 方标签；步骤时间线 1px；失败重跑按钮明确 | P2 |
| 22 | **管理端风险** `/admin/risk` | 卡片 + 彩色 pill；证据区域样式弱 | 队列表格；等级 1px 方标签；证据 `pre` 等宽滚动区；二次授权输入 1px；结案危险色 | P2 |
| 23 | **管理端内容** `/admin/content` | 原始 JSON `<pre>`；列表卡片；预览弱 | 表格 + 行展开；JSON 只读等宽区；发布/下架/回滚明确；预览结果分区；内容校验错误定位 | P2 |
| 24 | **管理端用户** `/admin/users` | 卡片列表；危机看板信息密度低 | 用户表 + 状态筛选；危机看板表格 + 迁移轨迹；冻结/解冻危险色；分页数字 tabular | P2 |
| 25 | **管理端运维** `/admin/ops` | 三个 Tab 胶囊；表格样式不统一；校验结果弱 | 左 Rail + 表格；审计动作 12px；链校验结果 1px 状态条；密钥/导出表格化 | P3 |
| 26 | **管理端群体看板** `/admin/board` | 卡片 + 成员列表；统计展示弱 | 群体表 + 成员表；抑制状态 1px 标签；统计表格；授权/未授权明确；隐私阈值说明 | P3 |
| 27 | **小程序全局** | iOS token；emoji 表情；大圆角；tabBar 系统色 | 同步 §5 token；去 emoji/渐变；1px 线；tabBar 扁平；安全区；暗色适配 | P1 |
| 28 | **小程序今日/日记/漫聊/我的** | 与 Web 重复但样式更“iOS”；信息密度低 | 按 Web 对应页的手记规范收敛；保留小程序交互习惯（下拉刷新/触底加载） | P1 |
| 29 | **小程序登录/通知/资源** | 登录品牌感重；资源页卡片化 | 登录字标 + 纸面表单；通知列表；资源页高对比电话 | P2 |

## 5.11 多端适配

- **Web 移动端**：375–767px，单列；底部 Tab；安全区；页面左右 20px。
- **Web 桌面端**：≥1024px，暖纸背景 + 760px 纸页列；不做手机壳；管理端 Rail 200px + 内容 1200px。
- **平板**：768–1023px，单列纸页列；可选折叠 Rail。
- **小程序**：rpx 与 px 换算保持 1px 边框；暗色用页面根 `.sv-dark`；tabBar 用系统配置但颜色对齐 token；长文同样使用衬线（若字体包体积不可接受，退回系统宋体）。
- **打印/导出**：黑白友好；隐藏交互；表格不截断；页眉页脚使用日期戳；日记/报告正文衬线。

## 5.12 UI 迁移策略

1. **Token 先行**：先在 `tokens.css` / `tokens.scss` 落地暖纸 Token，旧变量做别名过渡（一个版本），页面逐步替换。
2. **壳层先行**：App / Nav / TabBar / Card / Button / Input 六个基础件先改，再改页面。
3. **逐页迁移**：按 §5.10 顺序；每页完成即截图对比；不一次性全站替换。
4. **保留可用性优点**：旧 iOS 体系的 44px 触控、安全区、焦点样式、reduced-motion 支持保留；只替换视觉语言。
5. **不改业务**：UI 批次只动模板/样式/组件拆分，不动 API 调用、状态机、路由语义。
6. **验收**：0 装饰性渐变/毛玻璃/高光/彩色阴影/emoji 图标；对比度 AA；4 宽度 × 明暗截图；键盘走查；`prefers-reduced-motion` 走查；长文阅读列 68ch。

---

# 6. 验收标准

## 6.1 文档验收（本 Spec 自身）

- [x] 六个固定章节齐全：项目概况 / 现有模块清单 / 代码缺陷&重构方案 / 扩展功能规划 / UI 设计规范 / 验收标准。
- [x] 模块清单覆盖后端、Web、小程序；每个模块有职责、关键类、入口。
- [x] 业务流程覆盖日记、认知书写、模拟、漫聊、风险、打卡计划、档案导出、内容 KG、认证账号。
- [x] 数据关系覆盖 36 张表；明确逻辑关系、加密字段、软删除、无外键约束。
- [x] 接口清单覆盖 106 个操作；与 `openapi.json` 可对照。
- [x] 重构清单分为 A/B/C 三类；每项有位置、问题、方案、风险范围。
- [x] 扩展清单分高优/中优/备选；每项有依赖、成本、边界。
- [x] UI 规范包含 Token、组件、图表、动效、无障碍、逐页清单。
- [x] 验收标准可执行、可量化。
- [x] 仅新增 Spec 文档，未改任何业务代码。

## 6.2 行为等价验收（重构硬门槛）

| ID | 标准 | 验证方式 |
|---|---|---|
| V-01 | 同一输入产生同一任务状态序列 | 12 个 golden 场景重放；对比 `task_instance.status`、`task_step_log`、SSE 事件序列 |
| V-02 | 同一文本产生同一风险级别与危机迁移 | 规则回归集 + 高风险样例；对比 `risk_event.level`、`crisis_lifecycle` |
| V-03 | 加密字段格式与可解密性不变 | 测试密钥加密/解密；密文长度/版本字节；注销后不可解 |
| V-04 | 接口字段/错误码不漂移 | OpenAPI diff + golden JSON diff；仅批准字段可变 |
| V-05 | 页面主流程可完成 | 登录→写日记→分析→报告→练习→档案→导出；模拟→复盘；漫聊→收段 |
| V-06 | 管理端权限不变 | 普通用户访问 `/admin/**` 403；ADMIN 正常；RBAC 权限位生效 |
| V-07 | 数据一致 | 迁移前后行数/唯一键/状态分布一致；无孤儿关键记录 |
| V-08 | 性能不退化 | 日记列表 p95、任务提交 p95、SSE 首事件延迟不高于基线 10% |

## 6.3 重构验收

- A 类：全部完成；测试全绿；UI 截图仅预期变化；无接口/数据 diff。
- B 类：每项有批准记录、前后对比、回滚方案；核心链路回归通过。
- C 类：`git diff` 零改动；仅允许新增测试/外部适配。
- 包结构：ArchUnit 规则通过；Controller 不直接依赖 Repository；`common` 不依赖业务包。
- 依赖：新增依赖在白名单内；无高危 CVE；构建体积不显著增加。
- 文档：OpenAPI 重新生成；`schema.d.ts` 与后端一致；ADR/变更记录更新。

## 6.4 UI 验收

| ID | 标准 | 验证方式 |
|---|---|---|
| U-01 | 0 装饰性渐变、0 毛玻璃/高光、0 彩色阴影、0 emoji 图标；仅浮层允许一层柔和阴影 | 源码扫描 + 视觉走查 |
| U-02 | 所有颜色/间距/字号/圆角来自 token | 源码扫描；禁止硬编码色值 |
| U-03 | 圆角符合 8/6/12/3 规范（进度环/状态点除外） | 视觉/源码 |
| U-04 | 正文对比度 ≥4.5:1；大字 ≥3:1 | 对比度工具抽查 |
| U-05 | 键盘可完成登录/写日记/模拟/漫聊/设置 | 键盘走查 |
| U-06 | 375/768/1024/1440 × 明暗无溢出/无遮挡 | 截图矩阵 |
| U-07 | `prefers-reduced-motion` 下无动效 | 系统设置走查 |
| U-08 | 所有页面 loading/empty/error 三态 | 断网/空数据/错误注入 |
| U-09 | 危机页/转介卡高对比、电话可拨 | 真机/浏览器 |
| U-10 | 小程序与 Web 视觉语言一致 | 对照截图 |

## 6.5 扩展验收

- 每项扩展有独立变更单（ID、依赖、影响、开关、回滚）。
- 不引入白名单外依赖；新增依赖需说明体积/维护性/替代方案。
- 新功能默认关闭或灰度；失败不影响主链路。
- 观测：新增指标/日志/告警；有失败路径。
- 隐私：涉及群体数据必须通过隐私三闸；不输出个体原文。
- 安全：不新增越权/注入/SSRF/上传风险；通过安全审查清单。

## 6.6 禁止项与审批

- 未经批准，不得修改 §3.4 的 C 类文件。
- 未经批准，不得改变风险判定、危机状态机、加密格式、免责声明、隐私阈值。
- 未经批准，不得引入新的任务引擎、第二套状态管理、向量库、消息队列。
- 未经批准，不得改动历史数据库列语义；只允许新增迁移。
- 任何“顺手优化”如果触及 B/C 类，必须拆出并走审批。

## 6.7 交付物清单

| 交付物 | 路径 | 状态 |
|---|---|---|
| 全局基线 Spec | `.trae/specs/establish-soul-voyage-baseline/spec.md` | 本次交付 |
| 任务清单 | `.trae/specs/establish-soul-voyage-baseline/tasks.md` | 本次交付 |
| 验收清单 | `.trae/specs/establish-soul-voyage-baseline/checklist.md` | 本次交付 |
| 基线快照（后续 Batch 0 生成） | `docs/baseline/`（截图、golden JSON、OpenAPI 副本） | 待执行 |
| 重构变更单（后续） | 每项一个 change-id | 待执行 |

---

## 附：本 Spec 的关键判断摘要

1. **项目主体是健康的**：模块化单体、编排中心、Agent 隔离、加密与风险双轨都已落地；问题主要在“契约层、UI 层、多节点生产化、前端工程化”。
2. **最先做的不是加功能，而是冻结基线 + 收敛契约 + 统一 UI 语言**；否则后续扩展会继续放大重复与漂移。
3. **核心安全语义零改动**：加密、风险词表、危机状态机、输出校验、隐私阈值、免责声明是红线；只能在外部加适配与测试。
4. **UI 不是“换皮”**：从 iOS 果冻感切换到「静室手记」的暖纸、衬线阅读、低刺激视觉，需要 Token、壳层、组件、页面四层依次落地；但不得改变业务逻辑与路由语义。
5. **扩展优先级**：先做任务中心/洞察/计划/内容运营/可观测，再做语音/图谱/多端；RAG、实时语音、社区等重依赖项暂缓。
