# Batch 0 + Batch 1 实施计划

> 依据：`.trae/specs/establish-soul-voyage-baseline/spec.md`（已评审冻结，v1.0）
> 范围：Spec §3.6 的 **Batch 0（基线快照与护栏）** + **Batch 1（A 类低风险重构）**
> 约束：不超出 Spec；不改 B/C 类；核心/安全相关项单列，未确认不执行；本文件仅计划，不代表已改动代码。

---

## 1. 范围与门禁

### 1.1 本次执行范围

| 范围 | 内容 | 是否执行 |
|---|---|---|
| Batch 0 | 基线快照、C 类文件清单、`git diff` 守卫、测试报告归档 | 执行 |
| Batch 1 | A-01、A-03、A-05（仅无视觉差异部分）、A-06、A-07、A-08、A-09、A-10、A-11、A-17、A-18（核验，不改）、A-19、A-20、A-21、A-22 | 执行 |
| 暂不执行 | A-02 大视觉改版、A-04 巨型组件拆分、Batch 2+、全部 B/C 类 | 不执行 |
| 单列待确认 | A-12（错误码契约）、A-13/A-14（OrchestratorService）、A-16（Actuator 鉴权） | 未逐项确认前不执行 |

### 1.2 核心业务/安全边界（本次不碰）

以下文件在本次计划中**零改动**：

- `soul-backend/src/main/java/com/leyon/soul/crypto/**`
- `soul-backend/src/main/java/com/leyon/soul/agent/risk/RiskRules.java`、`resources/risk/rules.json`
- `soul-backend/src/main/java/com/leyon/soul/domain/crisis/**`
- `soul-backend/src/main/java/com/leyon/soul/llm/OutputValidator.java`、`resources/schemas/*.json`、`PromptTemplates` 免责声明
- `soul-backend/src/main/java/com/leyon/soul/audit/**`（哈希链）
- `deploy/sql/schema.sql` 既有列语义（只读复制，不修改）
- `domain/board/GroupBoardService` 隐私三闸
- `account/AccountService` 注销语义
- `wechat/WxBindTicketStore` 一次性票
- `docs/项目手册.md` 产品边界与危机话术
- `CrisisReferral.vue` / `CrisisView.vue` 的转介资源

### 1.3 已发现的 Spec 事实修正

- **A-18 已满足**：Spec 写“compose 的 app 未定义 healthcheck”，但 `soul-backend/Dockerfile:16-17` 已定义 `HEALTHCHECK` 调用 `/actuator/health`。`deploy/docker-compose.yml` 的 `web: depends_on app: service_healthy` 可以工作。**本次不改 compose**，只在基线报告中记录证据；Spec A-18 的描述在实施时同步修正为“已满足”。

---

## 2. 当前状态分析（证据）

| Item | 现状证据 | 结论 |
|---|---|---|
| A-01 | `soul-frontend/src/styles/base.css:15` 使用 `var(--sv-body)`；`tokens.css:47-59` 只有 `--sv-fs-body` | 确认，需改 1 行 |
| A-03 | `TodayView.vue:42-59` 与 `PracticeView.vue:12-32` 重复 `PlanItem/PlanView`；`InsightsView.vue:30-43` 定义 `Point/Week`；`AdminTasksView.vue:80-86` 管线选项与后端不一致 | 确认，抽 `types/domain.ts` + `constants/task.ts` |
| A-05 | 17 个视图含 `^\.body {`（Account/Achievements/Analysis/Archive/Companion/DiaryDetail/DiaryList/DiaryWrite/Insights/Letters/Me/Notifications/PracticeRoom/Practice/ReportDetail/Simulation/Today）；10 个视图含 `^\.center {`；`.t/.err/.ok` 各页声明不一致 | 只收敛**完全重复**的 `.body` 与 `.center`；`.t/.err/.ok` 变体留到 Batch 2，避免视觉漂移 |
| A-06 | `TodayView.vue:124/131/156`、`PracticeView.vue:79`、`InsightsView.vue:65-71`、`DiaryWriteView.vue:98`、`AccountView.vue:80` 使用浏览器本地 `toLocaleDateString('en-CA')` | 确认，抽 `utils/date.ts` 固定 Asia/Shanghai |
| A-07 | `soul-frontend/src/api/sse.ts:109` 判断 `CANCELED`；后端 `OrchestratorService.java:297-307` 写 `CANCELLED` | 确认，前端改为共享常量 |
| A-08 | `AdminTasksView.vue:80-86` 含 `SUPPORT_PIPELINE`/`REVIEW_PIPELINE`，缺 `COGNITIVE_PIPELINE`/`SIMULATE_PIPELINE` | 确认，随 A-03 常量修正 |
| A-09 | `PracticeView.vue:95` “4 场景”；`DiaryWriteView.vue:186` “四个 Agent” | 确认，改中性/数据驱动文案 |
| A-10 | `soul-frontend/src/composables/theme.ts:15-19` 的 `isDark` 只读一次 `matchMedia` | 确认，改用 `usePreferredDark` |
| A-11 | `AnalysisView.vue:219`、`ReportDetailView.vue:244` 模板 `(s: any)` | 确认，类型收窄 |
| A-17 | `deploy/nginx.conf:25` `client_max_body_size 1m`；后端允许 6MB、音频 5MB | 确认，改 6m |
| A-18 | `soul-backend/Dockerfile:16-17` 已有 HEALTHCHECK | **无需改代码**，仅验证并修正 Spec 描述 |
| A-19 | `soul-frontend/package.json` scripts 无 `typecheck` | 确认，新增 `vue-tsc -b` |
| A-20 | `DiaryListView.vue:61-64` 先 `page++` 再请求 | 确认，失败不自增 |
| A-21 | `PracticeView.vue:53-57` `Promise.all` 任一失败整页空 | 确认，改 `allSettled` |
| A-22 | `TodayView.vue:108` `hour` 只在 setup 取一次 | 确认，改响应式时间 |
| A-12 | `GlobalExceptionHandler.java:78-81` HTTP 500 但 body code=2001；`ErrorCode` 无 5000 | 单列待确认 |
| A-13 | `OrchestratorService.java:54` 虚拟线程执行器无 `@PreDestroy` | 单列待确认 |
| A-14 | `OrchestratorService.java:188/213` `nextConsumer` 恒返回常量；`TaskDtos` 未用 `@Valid`；`AgentCode.ALL` 未用 | 单列待确认 |
| A-16 | `SecurityConfig.java:50-54` `/actuator/**` 仅 authenticated | 单列待确认 |

**环境基线**：MySQL 3306、Redis 6379 已监听；8080/5173/5174 未启动；`soul-backend/target/soul-backend-0.1.0.jar` 存在（2026-09-29 构建）；`deploy/tools/node_modules` 有 `puppeteer-core`；Edge 位于 `C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe`。

---

## 3. 执行步骤

### Phase A — Batch 0：基线快照与护栏（先做，不改业务）

#### A1 静态快照（无需启动服务）

新增/复制：

| 文件 | 动作 |
|---|---|
| `docs/baseline/manifest.md` | 新增：基线提交、日期、环境、命令、已知限制、快照清单 |
| `docs/baseline/openapi.json` | 复制 `soul-backend/openapi/openapi.json` |
| `docs/baseline/schema.sql` | 复制 `deploy/sql/schema.sql`（只读副本） |
| `docs/baseline/schema.web.d.ts` | 复制 `soul-frontend/src/api/schema.d.ts` |
| `docs/baseline/schema.miniprogram.d.ts` | 复制 `miniprogram/src/api/schema.d.ts` |
| `docs/baseline/c-modules.txt` | 新增：C 类文件/目录清单（与 Spec §3.4 对齐） |
| `docs/baseline/reports/` | 复制现有 `soul-backend/target/surefire-reports/`、`soul-frontend/coverage/` 摘要（若存在） |

#### A2 C 类守卫脚本

新增 `deploy/tools/check_c_guard.mjs`：

- 读取 `docs/baseline/c-modules.txt`；
- 运行 `git diff --name-only`、`git diff --cached --name-only` 与 `git status --porcelain`；
- 若命中 C 类路径，输出 `BLOCKED` 并 `exit 1`；
- 支持 `--allow <path>` 仅用于专项批准单（默认不提供）。
- 只读脚本，不改业务代码。

#### A3 运行时 golden 与截图（需启动本地服务）

前置检查（已在计划阶段确认端口；执行时再确认）：

1. MySQL/Redis 已启动；
2. `cd soul-backend && mvn -DskipTests package`（若 jar 过期）；
3. 从 `deploy/local.env` 注入环境变量（PowerShell 逐行解析 `KEY=VALUE`，不打印密钥）；
4. `java -Dfile.encoding=UTF-8 -jar soul-backend/target/soul-backend-0.1.0.jar`；
5. `cd soul-frontend && npm run dev -- --port 5174`。

新增 `deploy/tools/snapshot_batch0.mjs`：

- 使用 `fetch` 调用后端，保存 golden JSON 到 `docs/baseline/golden/`；
- 场景按 Spec §3.6 列出的全部条目执行（Spec 文字写“12 个”，实际列出 15 条，以条目为准）：
  1. `register`（唯一用户名）
  2. `login`
  3. `refresh`
  4. `logout`
  5. 日记提交 + 轮询 `GET /tasks/{taskNo}` 至终态
  6. 高风险日记（含危机句式）后的 `risk/receipt`
  7. 模拟：`POST /simulations` → `POST turns`（SSE 只记录 final）→ `finish` → 轮询任务
  8. 漫聊：`POST sessions` → `POST turns` → `POST end` → 轮询任务
  9. 打卡 + 补签 + streak
  10. 计划 active/detail
  11. 报告 detail + feedback
  12. 档案 export + 领取
  13. 注销申请 + 撤回
  14. 管理端任务重跑（需 ADMIN）
  15. 群体看板抑制（需 ADMIN + 群体成员/授权数据）
- **ADMIN 前置**：本地库没有管理员种子时，用专用测试用户注册后执行一次 `UPDATE user SET role='ADMIN' WHERE username='<baseline_admin>'`（仅测试库、仅该用户）；不删除行，避免破坏审计/外键语义。若不允许写库，则 14/15 记为 `SKIPPED` 并在 manifest 说明。
- 保存时对响应做**字段排序 + 去除随机/时间字段**（`taskNo`、`traceId`、`createdAt`、`*At`、ID）后写入，避免 golden 因时间/ID 变化误报；同时在 manifest 记录“比较时忽略字段清单”。
- 若后端/前端启动失败：**停止**，在 `docs/baseline/manifest.md` 记录 `BLOCKED`，不进入 Batch 1；不伪造快照。

新增 `deploy/tools/snapshots_batch0.mjs`（或复用 `shots_m6.mjs` 的 Edge 配置）：

- 使用 `deploy/tools/node_modules/puppeteer-core` + 本机 Edge；
- 路由：`/today`、`/diaries`、`/diaries/write`、`/insights`、`/practice`、`/me`、`/login`；
- 宽度：375 / 768 / 1024 / 1440；明暗两色；
- 输出 `docs/baseline/screenshots/{width}-{theme}-{page}.png`；
- 只截图，不改代码；若某路由需要登录，使用 API 注册的测试账号注入 localStorage。

#### A4 Batch 0 出口

- `docs/baseline/manifest.md` 完整列出：提交、环境、命令、快照文件、忽略字段、失败/跳过项；
- 可复跑：`node deploy/tools/snapshot_batch0.mjs` 与 `node deploy/tools/check_c_guard.mjs`；
- **门禁**：静态快照 + C 守卫必须完成；运行时 golden/截图若因服务不可用而 BLOCKED，则暂停 Batch 1，汇报原因。

---

### Phase B — Batch 1：A 类低风险重构

> 只做纯前端结构/类型/样式收敛 + 部署配置 + 工具链；不碰 B/C，不碰 A-12/13/14/16。

#### B1 快速修复（A-01 / A-07 / A-08 / A-09 / A-20 / A-21 / A-22）

| 文件 | 改动 |
|---|---|
| `soul-frontend/src/styles/base.css` | `var(--sv-body)` → `var(--sv-fs-body)`（A-01） |
| `soul-frontend/src/api/sse.ts` | 引入 `TERMINAL_TASK_STATUSES`；终态判断改为 `CANCELLED`/`EXPIRED`；删除硬编码 `CANCELED`（A-07） |
| `soul-frontend/src/views/admin/AdminTasksView.vue` | 管线/状态选项改为 `constants/task.ts` 的 `PIPELINES`/`TASK_STATUSES`；补 `COGNITIVE_PIPELINE`、`SIMULATE_PIPELINE`，删 `SUPPORT_PIPELINE`、`REVIEW_PIPELINE`（A-08） |
| `soul-frontend/src/views/PracticeView.vue` | “4 场景”改为动态/中性文案（A-09） |
| `soul-frontend/src/views/DiaryWriteView.vue` | “四个 Agent”改为中性文案（A-09） |
| `soul-frontend/src/views/DiaryListView.vue` | `more()` 改为 `load(false, page.value + 1)`，成功后才提交页码；失败不前进（A-20） |
| `soul-frontend/src/views/PracticeView.vue` | `Promise.all` → `Promise.allSettled`；计划/记录/练习库分块降级；仅当 active plan 成功时拉取详情（A-21） |
| `soul-frontend/src/views/TodayView.vue` | `hour` 改为 `now` ref + 60s 定时 + `visibilitychange` 刷新；问候语用 `businessHour(now)`（A-22） |

#### B2 类型/常量/日期（A-03 / A-06 / A-11）

**新增文件**

| 文件 | 内容 |
|---|---|
| `soul-frontend/src/types/domain.ts` | `PlanItemView`、`ActivePlanView`、`PlanDetailView`、`EmotionPointView`、`WeekProfileView`、`ExerciseView`（从 `content` 的 `ExerciseDef` 复用/别名） |
| `soul-frontend/src/constants/task.ts` | `PIPELINES`、`PIPELINE_LABELS`、`TASK_STATUSES`、`TASK_STATUS_LABELS`、`TERMINAL_TASK_STATUSES` |
| `soul-frontend/src/utils/date.ts` | `BUSINESS_TZ='Asia/Shanghai'`；`businessDate(date?)`；`businessHour(date?)`；`addBusinessDays(date, n)`；`displayMonthDay(iso)` |

**修改文件**

| 文件 | 改动 |
|---|---|
| `TodayView.vue` | 删除本地 `PlanItem/PlanView`，改 import `PlanItemView/ActivePlanView`；`today()`/`buildDays` 用 `businessDate`；`from` 用 `addBusinessDays`；`PIPE_LABEL` 改用 `PIPELINE_LABELS`（A-03/A-06） |
| `PracticeView.vue` | 删除本地 `PlanItem/PlanView`，改 import `PlanItemView/PlanDetailView`；`today()` 用 `businessDate`（A-03/A-06） |
| `InsightsView.vue` | 删除本地 `Point/Week`，改 import `EmotionPointView/WeekProfileView`；`today`/`fmt`/`from` 用 `businessDate`/`addBusinessDays`（A-03/A-06） |
| `DiaryWriteView.vue` | `recordDate: businessDate()`（A-06） |
| `AccountView.vue` | 导出文件名日期用 `businessDate()`（A-06） |
| `AnalysisView.vue` | `exerciseNames` 显式 `computed<Record<string, ExerciseDef>>`；删除 `(s: any)`（A-11） |
| `ReportDetailView.vue` | 同上（A-11） |
| `soul-frontend/src/api/sse.ts` | 导入 `TERMINAL_TASK_STATUSES`（与 B1 同文件，按一次改完） |

**关键决策**：
- `PlanView` 字段不同：`ActivePlanView` 只含 Today 需要的字段；`PlanDetailView` 增加 `startDate/endDate/status`，不强行合并成一个宽类型。
- `businessDate` 使用 `Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai'})`，避免浏览器本地时区导致日期偏一天。
- 只改显示/查询参数，不改后端 `recordDate` 语义。

#### B3 主题响应（A-10）

| 文件 | 改动 |
|---|---|
| `soul-frontend/src/composables/theme.ts` | 引入 `usePreferredDark`；`isDark = computed(() => mode.value==='dark' || (mode.value!=='light' && preferredDark.value))` |
| `soul-frontend/src/composables/theme.spec.ts` | 保留现有 7 例；新增一例：系统主题变化后 `isDark` 跟随（mock `matchMedia` 保存 listener 并触发） |

#### B4 A-05：只收敛无视觉差异的重复样式

**新增**

| 文件 | 内容 |
|---|---|
| `soul-frontend/src/components/ui/SvPageBody.vue` | `<div class="sv-page-body"><slot/></div>` |
| `soul-frontend/src/styles/base.css` | 新增 `.sv-page-body { padding: 0 var(--sv-s4); }`、`.sv-center { text-align:center; padding: var(--sv-s4) 0; }` |

**迁移 `.body` → `SvPageBody`（17 个视图）**

`AccountView`、`AchievementsView`、`AnalysisView`、`ArchiveView`、`CompanionView`、`DiaryDetailView`、`DiaryListView`、`DiaryWriteView`、`InsightsView`、`LettersView`、`MeView`、`NotificationsView`、`PracticeRoomView`、`PracticeView`、`ReportDetailView`、`SimulationView`、`TodayView`。

做法：
- `<div class="body">` → `<SvPageBody>`（条件分支的 `<div v-if=... class="body">` → `<SvPageBody v-if=...>`）；
- 删除各文件 scoped `.body { padding: 0 var(--sv-s4); }`；
- 不改页面内部布局。

**迁移 `.center` → `.sv-center`（10 个视图）**

`AchievementsView`、`LettersView`、`NotificationsView`、`AdminBoardView`、`AdminContentView`、`AdminHomeView`、`AdminOpsView`、`AdminRiskView`、`AdminTasksView`、`AdminUsersView`。

做法：
- `class="center"` → `class="sv-center"`；
- 删除 scoped `.center { text-align:center; padding: var(--sv-s4) 0; }`；
- `NotificationsView` 的 `line-height: 1.7` 作为局部附加类保留，避免视觉变化。

**本次不改**：
- `.t`（6 个视图声明不一致）；
- `.err`（7 个视图 margin/font-size 不一致）；
- `.ok`（2 个视图语义不同）；
- 这些统一留给 Batch 2 逐页 UI 迁移，避免 A-05 变成视觉改版。

#### B5 部署与工具链（A-17 / A-18 / A-19）

| 文件 | 改动 |
|---|---|
| `deploy/nginx.conf` | `client_max_body_size 1m` → `6m`（与 `SV_UPLOAD_MAX_FILE` 对齐） |
| `deploy/docker-compose.yml` | **不改**；验证 `soul-backend/Dockerfile:16-17` 的 HEALTHCHECK，记录到 manifest |
| `soul-frontend/package.json` | 增加 `"typecheck": "vue-tsc -b"` |
| `.github/workflows/ci.yml` | 在 frontend job 的 “类型检查与构建” 前增加 `npm run typecheck`（若 Spec 要求 CI 卡点；只加一行） |

#### B6 测试与回归

- 新增 `soul-frontend/src/utils/date.spec.ts`：
  - `TZ=UTC` 下 `businessDate(new Date('2026-11-10T16:30:00Z')) === '2026-11-11'`；
  - `businessHour` 跨时区正确；
  - `addBusinessDays` 边界。
- 更新 `soul-frontend/src/api/sse.spec.ts`：覆盖 `CANCELLED`/`EXPIRED` 终态识别（若现有测试结构允许）。
- 更新 `soul-frontend/src/views/...` 无需新增组件测试；以现有 100 例 + 新增用例为准。
- 后端不改，`mvn test` 作为回归；不新增后端测试（A-12/13/14/16 未执行）。

---

## 4. 改动文件清单（汇总）

### 4.1 新增文件

| 文件 | 用途 | 所属 |
|---|---|---|
| `docs/baseline/manifest.md` | Batch 0 快照清单/环境/忽略字段 | Batch 0 |
| `docs/baseline/openapi.json` | 契约快照 | Batch 0 |
| `docs/baseline/schema.sql` | 表结构快照 | Batch 0 |
| `docs/baseline/schema.web.d.ts` | Web 生成类型快照 | Batch 0 |
| `docs/baseline/schema.miniprogram.d.ts` | 小程序生成类型快照 | Batch 0 |
| `docs/baseline/c-modules.txt` | C 类文件清单 | Batch 0 |
| `docs/baseline/golden/*.json` | Spec §3.6 全部 golden 场景（15 条，运行时） | Batch 0 |
| `docs/baseline/screenshots/*.png` | 4 宽度 × 明暗截图（运行时） | Batch 0 |
| `deploy/tools/check_c_guard.mjs` | C 类 diff 守卫 | Batch 0 |
| `deploy/tools/snapshot_batch0.mjs` | golden 采集 | Batch 0 |
| `deploy/tools/snapshots_batch0.mjs` | 截图采集 | Batch 0 |
| `soul-frontend/src/types/domain.ts` | 共享领域类型 | Batch 1 / A-03 |
| `soul-frontend/src/constants/task.ts` | 管线/状态常量 | Batch 1 / A-03+A-08 |
| `soul-frontend/src/utils/date.ts` | 业务时区日期工具 | Batch 1 / A-06 |
| `soul-frontend/src/utils/date.spec.ts` | 日期工具测试 | Batch 1 / A-06 |
| `soul-frontend/src/components/ui/SvPageBody.vue` | 页面内容容器 | Batch 1 / A-05 |

### 4.2 修改文件

| 文件 | 改动 | 所属 |
|---|---|---|
| `soul-frontend/src/styles/base.css` | 修复 `--sv-body`；新增 `.sv-page-body`/`.sv-center` | A-01/A-05 |
| `soul-frontend/src/api/sse.ts` | 终态常量修正 | A-07 |
| `soul-frontend/src/views/admin/AdminTasksView.vue` | 管线/状态选项 + `.center` 迁移 | A-08/A-05 |
| `soul-frontend/src/views/PracticeView.vue` | 文案、allSettled、类型、日期、`.body`/`.center` 迁移 | A-03/06/09/21/05 |
| `soul-frontend/src/views/TodayView.vue` | 类型、日期、问候语响应式、`.body` 迁移 | A-03/06/22/05 |
| `soul-frontend/src/views/InsightsView.vue` | 类型、日期、`.body` 迁移 | A-03/06/05 |
| `soul-frontend/src/views/DiaryWriteView.vue` | 文案、日期、`.body` 迁移 | A-06/09/05 |
| `soul-frontend/src/views/DiaryListView.vue` | 分页修复、`.body` 迁移 | A-20/05 |
| `soul-frontend/src/views/AccountView.vue` | 导出日期、`.body` 迁移 | A-06/05 |
| `soul-frontend/src/views/AnalysisView.vue` | 去掉 `any`、`.body` 迁移 | A-11/05 |
| `soul-frontend/src/views/ReportDetailView.vue` | 去掉 `any`、`.body` 迁移 | A-11/05 |
| `soul-frontend/src/views/AchievementsView.vue` | `.body`/`.center` 迁移 | A-05 |
| `soul-frontend/src/views/ArchiveView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/CompanionView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/DiaryDetailView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/LettersView.vue` | `.body`/`.center` 迁移 | A-05 |
| `soul-frontend/src/views/MeView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/NotificationsView.vue` | `.body`/`.center` 迁移（保留 line-height） | A-05 |
| `soul-frontend/src/views/PracticeRoomView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/SimulationView.vue` | `.body` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminBoardView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminContentView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminHomeView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminOpsView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminRiskView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/views/admin/AdminUsersView.vue` | `.center` 迁移 | A-05 |
| `soul-frontend/src/composables/theme.ts` | `isDark` 响应系统主题 | A-10 |
| `soul-frontend/src/composables/theme.spec.ts` | 新增/更新主题用例 | A-10 |
| `deploy/nginx.conf` | 上传上限 6m | A-17 |
| `soul-frontend/package.json` | 新增 `typecheck` | A-19 |
| `.github/workflows/ci.yml` | 前端 CI 增加 typecheck | A-19 |

> 注：`soul-frontend/src/views/admin/AdminTasksView.vue` 同时出现在 A-08 与 A-05；按同一文件一次改完，避免两次触碰。

---

## 5. 核心/安全相关，单列待确认（本次不执行）

> 以下虽在 Spec 中归 A 类，但按本次用户要求单独列出；**未得到逐项确认前，不修改**。

| ID | 文件 | 拟改动 | 风险 | 回滚 |
|---|---|---|---|---|
| A-12 | `soul-backend/src/main/java/com/leyon/soul/common/api/ErrorCode.java`、`common/exception/GlobalExceptionHandler.java` | 新增 `INTERNAL_ERROR(5000)`；未知异常 body code 从 2001 → 5000；新增 `GlobalExceptionHandlerTest` | 全接口错误契约；前端/脚本可能依赖 2001 | 回退两个文件；保留 HTTP 500 不变 |
| A-13 | `soul-backend/src/main/java/com/leyon/soul/orchestrator/OrchestratorService.java` | 增加 `@PreDestroy` 关闭虚拟线程执行器 | 核心编排/优雅停机；不影响任务语义 | 删除 `@PreDestroy` 方法 |
| A-14 | 同上 + `orchestrator/api/TaskDtos.java`、`orchestrator/AgentCode.java` | 删除 `nextConsumer`，直接写 `ORCHESTRATOR`；删除未用 `@Valid`；删除未用 `AgentCode.ALL` | 核心编排消息 `toAgent` 字段；必须逐字保持值 | 回退三处；`toAgent` 仍为 `ORCHESTRATOR` |
| A-16 | `soul-backend/src/main/java/com/leyon/soul/auth/SecurityConfig.java` | 在 health 放行之后增加 `/actuator/**` → `hasRole('ADMIN')` | 运维可观测性；普通用户 403；需确认 Prometheus 抓取方式 | 回退一行 matcher |

**确认方式**：在实施前回复 “A-12 批准 / A-13 批准 / A-14 批准 / A-16 批准” 的任意组合；未列出的保持不执行。

---

## 6. 风险点与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| A-05 迁移 17+10 个页面 | 视觉/布局回归面广 | 只迁移**完全重复**的 `.body`/`.center`；不改内部布局；逐页截图对照；保留页面局部覆盖 |
| A-06 时区变化 | 日期显示从本地时区改为 Asia/Shanghai，跨时区用户可能看到日期变化（预期修复） | 固定时区测试；golden 中记录日期字段；不改变后端记录语义 |
| A-07 取消状态识别 | 取消任务现在会正确进入终态；轮询行为变化（预期修复） | 单测覆盖 `CANCELLED`/`EXPIRED`；手动取消任务验证 |
| A-21 `allSettled` | 部分请求失败时页面显示局部空态，而非整页错误 | 每个块保留 loading/empty 文案；不吞掉整体错误提示 |
| A-20 分页 | 失败不再前进页码；成功行为不变 | 断网/500 注入验证 |
| A-10 主题监听 | `usePreferredDark` 在测试环境需 matchMedia mock | 更新 `theme.spec.ts`；保留现有 7 例 |
| A-19 typecheck | 可能暴露既有类型错误 | 先跑基线；只修本次引入的错误；既有错误登记到 manifest，不顺手重构 |
| Batch 0 启动服务 | 端口/DB/Redis/环境变量问题；会写入测试数据 | 端口预检；唯一测试用户名；不建表/不改 schema；失败即 BLOCKED 并停止 |
| Batch 0 golden 忽略字段 | 过度忽略会掩盖真实回归 | 只忽略 `taskNo/traceId/createdAt/*At/ID`；业务字段全量保留；manifest 列明 |
| A-18 误改 | compose 已可通过 Dockerfile healthcheck 工作 | 本次不改；只在 manifest/Spec 修正描述 |
| 误碰 C 类 | 安全/合规风险 | `check_c_guard.mjs` 在每批结束运行；C 类零 diff 作为门禁 |
| 范围蔓延 | 超出 Spec | 任何 A-02/A-04/B/C 需求先回到 Spec 登记，不在本批顺手做 |

---

## 7. 回滚方案

- **Batch 0**：全部为新增 docs/scripts；回滚 = 删除 `docs/baseline/` 与 `deploy/tools/check_c_guard.mjs`、`snapshot_batch0.mjs`、`snapshots_batch0.mjs`。不触碰数据库结构。
- **Batch 1**：按文件分组提交，便于单组 revert：
  - B1 快速修复一组；
  - B2 类型/日期一组；
  - B3 主题一组；
  - B4 `.body/.center` 迁移一组；
  - B5 部署/工具链一组。
- 不涉及表结构、接口字段、加密格式；回滚无数据迁移。
- 若 A-05 某页出现视觉回归：只回退该页的 `SvPageBody`/`sv-center` 改动，不影响其他页。

---

## 8. 验证步骤

### 8.1 Batch 0 验证

1. `node deploy/tools/check_c_guard.mjs` → 当前工作树应 `PASS`（除本计划新增的 docs 外）。
2. `docs/baseline/` 文件齐全；`manifest.md` 记录提交、环境、命令、忽略字段、BLOCKED 项。
3. 若运行时快照执行：Spec §3.6 全部 golden 场景文件存在且 JSON 可解析（允许 14/15 因无 ADMIN 记 SKIPPED）；4 宽度 × 明暗截图存在。
4. 不修改任何业务代码；`git status` 仅新增 `docs/baseline`、`deploy/tools/*` 与 Spec 计划文件。

### 8.2 Batch 1 验证

| 命令 | 期望 |
|---|---|
| `cd soul-backend && mvn test` | 现有测试全绿（401 例口径；9 例 Neo4j 跳过） |
| `cd soul-frontend && npm run lint` | 0 error |
| `cd soul-frontend && npm run format:check` | 通过 |
| `cd soul-frontend && npm run typecheck` | 通过（新增脚本） |
| `cd soul-frontend && npm run test` | 现有 100 例 + 新增日期/SSE 用例全绿 |
| `cd soul-frontend && npm run coverage` | 不低于现有逻辑层基线 |
| `cd soul-frontend && npm run build` | 成功 |
| `cd soul-frontend && npm run gen:api && git diff --exit-code -- src/api/schema.d.ts` | 无契约漂移 |
| `cd miniprogram && npm run typecheck && npm run build:mp-weixin && npm run build:h5` | 通过（未改小程序，应无变化） |
| `node deploy/tools/check_c_guard.mjs` | 0 C 类 diff |
| 手动/截图 | 登录→今日→日记→写日记→洞察→练习→我的→管理端任务；375/768/1024/1440 无溢出 |

### 8.3 完成定义（DoD）

- Batch 0 静态快照 + C 守卫完成；运行时快照完成或明确 BLOCKED；
- Batch 1 所有执行项完成，A-12/13/14/16 未改；
- 所有测试/构建通过；
- `git diff` 无 C 类文件；
- 更新 `tasks.md`：Task 5、Task 6 及其子项勾选；未执行项保持未勾选并注明原因；
- 更新 `checklist.md`：G3/G4 勾选；G5+ 保持未勾选；
- 输出变更摘要与风险回归结果。

---

## 9. 已确认决策与批准点

已确认（上一轮问答）：

1. 本次执行 **Batch 0 + Batch 1**。
2. **A-12 / A-13 / A-14 / A-16 全部单列，默认不执行**；未逐项确认前不改。

本计划默认（批准计划即视为同意）：

3. A-05 在 Batch 1 **只收敛 `.body` 与 `.center`**；`.t/.err/.ok` 的页面差异留到 Batch 2，避免本批变成视觉改版。
4. Batch 0 的运行时 golden/截图会启动本地 MySQL/Redis + 后端 + 前端；若启动失败，记录 `BLOCKED` 并暂停 Batch 1，不伪造快照。
5. A-18 不改 `docker-compose.yml`；以 `soul-backend/Dockerfile:16-17` 的 HEALTHCHECK 为准，并在 manifest / Spec 中修正描述。

若希望把 A-12/13/14/16 中的任意一项纳入本次执行，请在批准计划时逐项列出；否则默认跳过。
