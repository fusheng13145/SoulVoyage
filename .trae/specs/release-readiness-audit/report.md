# soul-voyage 发布就绪审计报告

> 审计日期：2026-11-12
> 范围：当前工作树（Batch 7A 已提交 `e7e4483`，另有本次审计修复未提交）
> 结论口径：**Web 首发具备功能基础，但未达到可公开上线状态；App 版尚未进入实施阶段。**

## 1. 结论摘要

| 维度 | 结论 | 说明 |
|---|---|---|
| 构建/启动 | **通过** | 后端 jar 本机启动成功；Web `typecheck/build`、小程序 `typecheck/lint/build:mp-weixin` 通过 |
| 自动化回归 | **通过** | 后端 484 例 0 失败 9 跳过；Web 116 例；小程序 20 例 |
| 核心链路（Mock） | **通过** | 日记流水线四步 SUCCESS；golden 15 场景执行完成 |
| 本机 Ollama | **部分通过，不建议作为验收/演示主方案** | EMOTION 稳定；TRACE 偶发成功；SUPPORT 失败；整条流水线 PARTIAL_SUCCESS |
| 技术债 | **可控但存在 P0 项** | 结构化输出契约、生产密钥、备份/监控、合规文案、依赖漏洞 |
| Web 上线 | **P0 完成后可发布** | 功能完整；缺域名/备案/证书/真实供应商/监控/备份/合规 |
| App 上线 | **未就绪** | 无原生打包、推送、签名、商店合规与设备会话管理 |

**一句话判断：** 当前是一个“功能完整、工程测试扎实、但生产基础设施与合规尚未闭环”的可演示系统；不能直接对外公开运营。

## 2. 当前环境与可运行性

### 2.1 已确认可用

- MySQL `127.0.0.1:3306`、Redis `127.0.0.1:6379` 可达。
- 后端 fat jar 可启动（`soul-backend/target/soul-backend-0.1.0.jar`），Flyway 校验 4 个迁移、schema 版本 3。
- Ollama `0.40.14` 可达（`http://127.0.0.1:11434`），已安装 `deepseek-r1:8b`（Qwen3 架构 8.2B，Q4_K_M，上下文 131072）。
- Ollama OpenAI 兼容端点 `/v1/chat/completions` 可用；流式以 `data: [DONE]` 收尾。

### 2.2 构建与测试

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `mvn -q test` | 484 tests / 0 failures / 0 errors / 9 skipped（68 个 surefire 文件） |
| Web | `npm run test` / `npm run build` | 116 tests / 12 files；构建通过 |
| 小程序 | `npm run test` / `lint` / `build:mp-weixin` | 20 tests / 4 files；lint 与 mp-weixin 构建通过 |
| Golden 15 | `node deploy/tools/snapshot_batch0.mjs` | 15/15 场景执行；`14-admin-task/retry` 因无 FAILED 任务 SKIPPED；仅 14/15 动态噪声，已还原 |

### 2.3 本次审计修复（未提交）

| 问题 | 修复 |
|---|---|
| Prompt 未注入输出 Schema，真实模型无法稳定猜出字段层级 | `PromptTemplates` 按模板注入对应 `schemas/*.json`，保证 Prompt 与 `OutputValidator` 同源 |
| reasoning 模型思维链吃满输出，`content` 为空 | 新增 `soul.llm.max-tokens-floor`（`SV_LLM_MAX_TOKENS_FLOOR`）与 `SV_LLM_TIMEOUT` |
| 可选字段 `null` 被 Schema 拒绝 | `OutputValidator` 先按原样校验；仅失败时才剪除对象 null 字段并重试；Schema 允许 null 的字段（如 `riskEventId`）保留 |
| 缺少对应回归 | 新增 `PromptTemplatesTest`、`OutputValidatorTest`；`M10OpenAiStreamTest` 增加 floor 断言 |

## 3. 核心功能验收

| 功能 | 结果 | 证据 |
|---|---|---|
| 注册/登录/刷新/登出 | 通过 | golden 1–4；`AuthHardeningTest` |
| 日记流水线 EMOTION→TRACE→SUPPORT→RISK_ARCHIVE | 通过（Mock） | 实跑四步 SUCCESS；`DiaryPipelineIntegrationTest` |
| 报告列表/详情/收藏/反馈 | 通过 | golden 11；`M10ReportAnnotationTest` |
| 计划物化/自适应/“今天不做”/理由 | 通过 | golden 10；`PlanServiceTest`；实跑 `reasons` 返回 |
| 练习跟练/记录 | 通过 | `ExerciseController` 测试与 golden 9 |
| 档案周报/限时一次性导出 | 通过 | golden 12；`ArchiveExportTest` |
| 漫聊/模拟训练/风险回执 | 通过 | golden 7/8/6；对应集成测试 |
| 管理端任务/风险/内容/审计/看板 | 通过 | golden 14/15；M9 系列测试 |
| 小程序核心页 | 通过 | 小程序 20 例 + mp-weixin 构建 |

## 4. 本机 Ollama 评估（当前模型方案）

### 4.1 实测结果

| 配置 | EMOTION | TRACE | SUPPORT | 总体 |
|---|---|---|---|---|
| floor 4096 / timeout 300s | SUCCESS（10.5s） | DEGRADED（根节点非 object） | 未执行 | PARTIAL_SUCCESS，120s |
| floor 8192 / timeout 600s | SUCCESS（9.3s） | SUCCESS（56.8s） | DEGRADED（输出不是合法 JSON） | PARTIAL_SUCCESS，186s |
| floor 16384 / timeout 900s | SUCCESS（11.4s） | DEGRADED（输出不是合法 JSON） | 未执行 | PARTIAL_SUCCESS，75s |

### 4.2 判断

- `deepseek-r1:8b` 是 reasoning 模型，单步会先输出大量思维链；在 8B 本地推理下，结构化 JSON 的稳定性与延迟都不满足交互式流水线要求。
- EMOTION 提示词自带示例，稳定通过；TRACE/SUPPORT 需要完整 Schema 与更长输出，仍会因思维链耗尽或返回非 JSON 而降级。
- 因此：**Ollama 可作为开发期联调选项，但不建议作为当前验收/演示的主方案**；主线验收继续使用 `mock`。

### 4.3 建议

1. 优先拉取非 reasoning 的 instruct 模型（如 `qwen2.5:7b-instruct` 一类），设置：
   - `SV_LLM_PROVIDER=openai`
   - `SV_LLM_BASE_URL=http://127.0.0.1:11434/v1`
   - `SV_LLM_MODEL=<instruct 模型>`
   - `SV_LLM_TIMEOUT=300s`
   - `SV_LLM_MAX_TOKENS_FLOOR=4096`
2. 若必须使用 `deepseek-r1:8b`，需接受 1–3 分钟/步的延迟，并继续提高 floor（16k+）与超时；即便如此仍不保证 JSON 稳定，不建议用于演示。
3. Ollama 只覆盖 LLM；语音日记的 ASR 仍需云端 ASR 或本地 Whisper 兼容服务（如 faster-whisper-server），当前 `SV_ASR_PROVIDER=mock` 不能用于生产。

## 5. 技术债清单

### P0（上线前必须处理）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | 真实 LLM 结构化输出契约此前缺失，Ollama 仍不稳定 | `PromptTemplates`（已修）；Ollama 实测 | 生产选定 instruct/托管模型；保留 Schema 注入；上线前跑真实 provider 冒烟 |
| 2 | 生产密钥存在开发默认值 | `application.yml`：`SV_JWT_SECRET` 默认 DEV_ONLY、`SV_MYSQL_PASS` 默认 CHANGE_ME、`SV_MASTER_KEY` 为空则临时生成 | 生产 `.env` 注入强随机值；MasterKey 必须备份，丢失即不可解密 |
| 3 | 隐私政策/用户协议/危机边界文案未终审 | `docs/项目手册.md:1420` 明确未勾选；仓库无对应页面 | 法务/心理顾问签字后上线；界面明示“不替代咨询/不诊断” |
| 4 | 无备份恢复与外部监控方案 | `deploy/docker-compose.yml` 只有数据卷；仓库无 backup/mysqldump/Sentry/Prometheus 配置 | 制定 MySQL/Redis/Neo4j 备份、异机保存、恢复演练；接入 uptime/错误追踪 |
| 5 | 依赖漏洞 | Web `npm audit`：3 high（follow-redirects、source-map-js、vite）；小程序：37 项（20 high，多为 uni-app 工具链） | Web 先 `npm audit fix` 并复跑构建；小程序评估 uni-app 升级，升级前记录风险接受 |
| 6 | CI 无 SCA/SAST/镜像构建/部署冒烟 | `.github/workflows/ci.yml` 只有测试/覆盖率/构建 | 增加 Dependabot/CodeQL 或 OWASP Dependency-Check；增加 Docker build 与 compose smoke |

### P1（首发前尽量处理）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 7 | 前端超时硬编码，慢模型下易误判失败 | `soul-frontend/src/api/http.ts` 30s；`miniprogram/src/api/sseText.ts` 65s | 抽成环境配置；本地/慢供应商单独放宽；UI 明确“仍在生成” |
| 8 | Web API 层保留 `any` 逃生口 | `soul-frontend/src/api/http.ts:12-14` | 逐域收窄到 OpenAPI 生成类型 |
| 9 | 管理端风险证据解密失败只返回错误文案，无日志 | `AdminRiskController.java:85-90` | 记录 warn + 事件 ID，便于运维定位 |
| 10 | ECharts chunk 约 581KB（gzip 196KB） | `soul-frontend/vite.config.ts`；构建输出 | 洞察页懒加载/按需引入图表类型 |
| 11 | README 与 compose 漂移 | `README.md:54` 称 Neo4j 未编排；`deploy/docker-compose.yml` 已含 neo4j | 更新文档，避免运维按旧说明部署 |
| 12 | `check_release` 未校验 MySQL/Redis 密码强度 | `deploy/tools/check_release.mjs` | 增加非空/长度/占位符检查；与 compose 的 `:?` 对齐 |
| 13 | 真机无障碍盲测未完成 | `docs/项目手册.md:1416` 未勾选 | 组织 30 分钟暗色/读屏/键盘盲测 |

### P2（可排后）

- `AchievementService` 等处存在空 catch（尽力而为语义，建议补 debug 日志）。
- 小程序与 Web 的 SSE 超时口径不同，建议统一常量。
- Neo4j 默认关闭即可；无图需求时不要在生产强制开启。
- 文档中测试基线数字（151/401）已滞后，当前为 484。

## 6. 发布前需要你亲自完成的事务清单

### P0（阻塞上线）

1. **选定生产 LLM 供应商与模型**：填 `SV_LLM_PROVIDER/BASE_URL/API_KEY/MODEL`；跑 `smoke_m10.mjs --llm` 与真实账单核对。
2. **选定 ASR 供应商**：云端或本地 Whisper 兼容服务；跑 `smoke_m11.mjs --asr`。
3. **域名与备案**：完成 ICP 备案、DNS 解析、HTTPS 证书；填 `SV_DOMAIN`、`SV_CORS_ORIGINS`、`deploy/certs/fullchain.pem`、`privkey.pem`。
4. **生成并保管生产密钥**：`SV_MASTER_KEY`（base64 32B）、`SV_JWT_SECRET`（≥32）、MySQL/Redis/Neo4j 密码；MasterKey 离线备份。
5. **合规文本终审**：用户协议、隐私政策、危机边界声明、数据删除/导出说明；心理顾问/法务签字。
6. **PIPL/隐私评估**：收集清单、用途、保存期限、第三方 SDK/模型跨境传输、未成年人保护、撤回同意。
7. **备份与恢复**：MySQL/Redis/Neo4j 定时备份、异机保存、至少一次恢复演练。
8. **监控告警**：站点可用性、5xx、任务失败率、LLM 成本/延迟、磁盘/连接数；明确值班人。
9. **微信小程序发布**：AppID/Secret、request 合法域名白名单、隐私协议、类目审核、图标/截图、提审。
10. **生产部署与回滚**：`docker compose --env-file local.env up -d --build`、nginx TLS、健康检查、冒烟、回滚镜像与数据库备份。

### P1（首发前尽量完成）

11. 依赖漏洞修复与 SCA/SAST 接入；评估小程序 uni-app 升级。
12. 用真实供应商重跑压测与预算；校准 `SV_LLM_*` 限额、队列、超时。
13. 无障碍盲测（暗色/读屏/键盘）。
14. 内容运营：种子内容、科普文案、危机热线资源复核。
15. 安全加固：WAF/限流、Bot 防护、密钥扫描、渗透测试。
16. 审计日志保留与导出、事件响应预案。

### P2（可上线后迭代）

17. App 打包与商店上架（见 §8）。
18. ECharts 懒加载、图片/字体优化。
19. 文档与基线数字更新。

## 7. Web 首发差距

**已具备：** 核心业务闭环、PWA/manifest/service worker、Web 与小程序双端、管理端、自动化测试与 CI 覆盖率门禁、Docker compose 与 TLS nginx 模板。

**缺口：**
- 生产供应商与密钥（P0）。
- 域名/备案/证书（P0）。
- 合规文本与隐私评估（P0）。
- 备份/监控/告警（P0）。
- 依赖漏洞与 SCA（P0）。
- 真实供应商下的延迟/成本/降级演练（P1）。
- 无障碍盲测与内容运营（P1）。

**判断：** P0 全部完成后，Web 版可以作为“受控内测/小范围公开”发布；不建议直接大规模推广。

## 8. App 化差距

当前基线明确将原生 App 列为 O-06 暂缓，仓库只有 Web PWA 与微信小程序，没有 Capacitor/uni-app App 打包配置。

### 8.1 技术路线建议

- **优先方案（质量优先）**：用现有 uni-app 小程序代码扩展 App 端（同一 Vue3 技术栈），核心页面原生化；Web 保持 PWA。优点是商店审核风险低、推送/权限/离线可控。
- **备选方案（最快）**：Capacitor 包装现有 Web。功能 parity 最好，但纯 WebView 包装在 App Store 审核中可能被认定为“网站套壳”，且推送/安全存储需要额外插件。

### 8.2 功能与基础设施缺口

| 类别 | 缺口 |
|---|---|
| 原生能力 | APNs/FCM/厂商推送、深链、Keychain/Keystore 安全存储、生物识别锁、后台同步、离线音频队列 |
| 账户与设备 | 设备会话管理（M-04 暂缓）、推送 token 注册、异常登录提示、单设备下线 |
| 发布工程 | iOS 证书/描述文件、Android keystore、签名 CI、灰度发布、强制更新/版本门槛 |
| 商店合规 | Apple/Google/国内商店开发者账号、隐私清单、健康/心理类目审核、年龄分级、应用内删除账号、IAP（如收费） |
| 国内合规 | ICP 备案、软件著作权、隐私政策、SDK 清单、未成年人模式、数据本地化 |
| 运营 | 崩溃上报、埋点、客服、内容审核、危机干预 SOP |
| 后端 | 推送服务、App 版本 API、功能开关、设备管理、离线冲突策略 |

**判断：** App 不是“打包一下”的工作，至少需要一轮独立的产品/合规/发布工程；建议 Web 首发稳定后再启动。

## 9. 建议执行顺序

1. 先做 P0 中的**密钥/域名/证书/供应商**，让 `check_release` 归零。
2. 补**合规文本与隐私评估**，这是心理类产品的一票否决项。
3. 补**备份/监控/告警**，再做一次恢复演练。
4. 修依赖漏洞，接入 SCA/SAST，复跑全门禁。
5. 用真实供应商做一轮端到端冒烟 + 压测 + 成本核对。
6. 小范围内测，收集真实反馈后再决定 App 投入。

## 10. 本次审计未提交的变更

- `soul-backend/src/main/java/com/leyon/soul/llm/PromptTemplates.java`
- `soul-backend/src/main/java/com/leyon/soul/llm/OutputValidator.java`
- `soul-backend/src/main/java/com/leyon/soul/llm/OpenAiCompatibleClient.java`
- `soul-backend/src/main/resources/application.yml`
- `soul-backend/src/test/java/com/leyon/soul/llm/{PromptTemplatesTest,OutputValidatorTest,M10OpenAiStreamTest}.java`
- `deploy/local.env.example`

这些变更解决的是“真实模型输出契约”和“本地 reasoning 模型 token/超时”问题，已通过后端全量测试与 mock golden；是否提交由你确认。
