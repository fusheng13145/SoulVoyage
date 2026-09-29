# 心屿漫行 SoulVoyage

> 面向大学生群体的任务驱动型多智能体协同心理自助成长平台。
> 仅为自助辅助工具，不做心理诊断、不替代心理咨询师；高危信号触发转介提示。

## 文档

- [项目手册 · 唯一事实源（集成版 v2.1）](docs/项目手册.md) —— 上篇系统设计（架构、调度中心、六大 Agent、数据层、API、安全）＋ 下篇产品深化设计 V2（功能扩展体系、iOS 设计系统、M5–M19 路线图与上线门禁，随各里程碑持续更新实现注记）＋ 附录 A 原始定稿存档 ＋ 附录 B 手册治理协议。原《定稿说明》《产品深化设计》两份文档已并入本手册并退役。
- [V2 交互原型](docs/demo/V2交互原型.html) —— 浏览器直接打开（纯前端假数据），M6 视觉与交互基线

## 仓库结构

```
backend/      SpringBoot 3 + Java 21 单体（orchestrator / agent / llm / kg / crypto / asr / wechat / admin / auth ...）
frontend/     Vue3 + TypeScript + Vite（Web 端，iOS 设计系统）
miniprogram/  uni-app + Vue3 + TS（微信小程序端，M14：微信只做既有账号的第二入口）
deploy/       sql/schema.sql（库表唯一事实源）+ sql/migrate_mN.sql · neo4j/seed.cypher · tools/（smoke/walk/load/llm_stub/asr_stub 脚本）· docker-compose.yml（预置未运行）
docs/         项目手册.md（唯一事实源）· demo/V2交互原型.html（M6 视觉基线）
```

## 本地开发（当前阶段：无 Docker，全部使用本机服务）

| 依赖 | 本机情况 | 说明 |
|---|---|---|
| JDK | 21 | 虚拟线程已启用 |
| Maven | 3.9 | |
| MySQL | 8.4 @127.0.0.1:3306（服务 MySQL84） | 库 `soulvoyage`，账号 `soulvoyage`，先导入 `deploy/sql/schema.sql` |
| Redis | @127.0.0.1:6379 免密 | 会话上下文 / 任务态 / JWT 黑名单 |
| Neo4j | 5.26.12 @127.0.0.1:7687（`D:/neo4j-community-5.26.12`） | 本机实例已就绪并导入 `deploy/neo4j/seed.cypher`（116 节点 / 178 关系，与 `resources/kg/*.json` 同源）；启动/导种子/查数走 `deploy/tools/neo4j.sh start\|seed\|status\|cy`（官方 `neo4j.bat` 在本机 PowerShell 5.1 下有 hashtable 键冲突缺陷，脚本内已注释 Java 直调绕过方式）。**M16 已落地**：`neo4j-java-driver 5.26.0` + `Neo4jKgService`（按 `content:version` 懒同步的只读派生视图，MySQL `kg_node` 仍是唯一写入口）+ `Neo4jKgConfig`（`SV_NEO4J_ENABLED=true` 时以 `@Primary` 接管）；图不可用时秒级回落 `kg_node` 兜底（驱动超时 2s + 30s 短路熔断）。开关关闭时装配图与 M14 及之前**逐字一致** |
| LLM | 无 Key | `SV_LLM_PROVIDER=mock` 走 MockLlmClient；`openai` 即 OpenAI 兼容真流式网关（M10 落地，真协议由 `deploy/tools/llm_stub.mjs` 本机桩钉住），密钥就绪后配置切换即可 |
| ASR | 无 Key | `SV_ASR_PROVIDER=mock`；`openai` 兼容转写（M11 落地，本机桩 `asr_stub.mjs`） |

```bash
# 后端（注意：中文路径下 mvn spring-boot:run 的 fork 会 ClassNotFound，用 jar 方式跑）
cd backend
mvn -DskipTests package
set -a && . ../deploy/local.env && set +a    # Git Bash；含本机 MySQL 密码与开发密钥
java -Dfile.encoding=UTF-8 -jar target/soulvoyage-backend-0.1.0.jar   # http://localhost:8080

# 导入/重建数据库（Windows 必须指定客户端字符集）
mysql -u root -p --default-character-set=utf8mb4 < deploy/sql/schema.sql

# 前端
cd frontend
npm install && npm run dev                   # http://localhost:5173（代理 /api → 8080）

# 微信小程序端（uni-app + Vue3；本机无 HTTPS 域名，走 H5 目标验证）
cd miniprogram
npm install && npm run dev:h5                # 构建产物 build:mp-weixin（真机需上线域名后）
```

## 后续 Docker 化规划

`deploy/docker-compose.yml` 已编排 mysql/redis/app/web 四服务（Neo4j 暂不编排，见 compose 内注释——应用侧 driver 已于 M16 接入，补编排只剩"加一段 neo4j 服务定义 + `SV_NEO4J_URI` 指向容器名"）；切换时仅需：
1. `docker compose --env-file local.env up -d --build`，应用 datasource/redis 主机名指向容器（`SV_MYSQL_HOST=mysql` 等，生产必须显式选 `SV_LLM_PROVIDER`，禁默认 mock）；
2. 数据卷挂载 `deploy/{mysql,redis}-data/`（已在 .gitignore 排除）；
3. 密钥经 local.env / compose env 注入 `SV_MASTER_KEY`、`SV_JWT_SECRET`、LLM Key。

## 里程碑

**M0–M14 全部完成 ✅ · M16 Neo4j 实装完成 ✅ · M18 图模式收口完成 ✅（M15 上线收口待办）**（各里程碑范围与验收明细以[项目手册 §10.2 / §11.3](docs/项目手册.md)为准）：

- **M0–M4 基础盘**：骨架+建库+登录 → 调度中心+LLM 网关(Mock)+情绪感知 Agent+AgentFlow 可视化 → 溯源推理+KG 约束+复盘报告 → 人际模拟训练（场景卡+NPC 导演+复盘评分）→ 疏导干预+风险双轨研判归档+成长档案导出
- **V2 深化（2026-09-20 完成）**：
  - M5 安全闭环+日记本（危机生命周期 / 注销即遗忘+数据导出 / 认证加固 / SSE 断线重放）
  - M6 iOS 设计系统（token+Sv 组件库 / 暗色 / PWA / a11y 98–100）
  - M7 成长陪伴（树洞漫聊+逐轮危机一票拦截 / 打卡·热力图·成就·计划·提醒·来信 / 沉浸跟练）
  - M8 内容与训练生态（KG 77 节点 / 场景×10 / 内容热更新 / 闭集反编造）
  - M9 管理端+生产化（A1–A5 / token 成本观测 / GitHub Actions CI / 1k DAU 压测 0% 错误率）
  - M10 上线门禁收口+真实 LLM 网关（OpenAI 兼容真流式 / 越权矩阵 25 行 / 375px+键盘避让走查）
- **远期首四项（2026-09-21 完成）**：
  - M11 语音日记（AsrClient 供应商抽象 / 音频零落盘 / "零改动 Agent"可复跑断言）
  - M12 辅导员群体看板（密文不出查询 / <10 人抑制 / 贡献者≥3 三道隐私闸）
  - M13 多节点状态面（闸门·滑动窗·事件流·导出快照四份状态下 Redis，`SV_DISTRIBUTED` 开关，双实例真机验证）
  - M14 微信小程序多端（uni-app 九页 / 一次性绑定票 / 微信只做既有账号第二入口）
- **M15 上线收口（待办 · P0，阁下指示暂缓）**：接真实 LLM/ASR 密钥复跑 `smoke_m10.mjs --llm` 与 `smoke_m11.mjs --asr` + 同参数复压测；`docker compose` 生产部署（补 Neo4j 服务编排）；域名/HTTPS/备案与小程序 `ONLINE_ORIGIN` 落地
- **M16 Neo4j 实装（2026-09-29 完成 ✅）**：清偿已知缺口 G3
  - `neo4j-java-driver 5.26.0` + `Neo4jKgService`（Cypher 召回与 `DbKgService` **逐字等价**；MySQL `kg_node` 仍是唯一写入口，Neo4j 是 `content:version` 驱动的只读派生视图）+ `Neo4jKgConfig` 条件装配（`SV_NEO4J_ENABLED`，沿用 M13 `@Primary` 范式）
  - `ContentStore.version()` 暴露版本号；`Neo4jKgContractTest` 7 例契约对拍（有真图实例时执行）+ `Neo4jKgCircuitBreakerTest` 2 例熔断单测（零依赖恒跑）
  - 真机验证：`smoke_m8.mjs` 九项全绿（含 N4 热更新 `contentVersion` 46→48、C3 复盘引用 `cc_apology_repair`）；图库计数与 `kg_node` 上架内容精确对齐（`:SvManaged` 77 + 闭集 Emotion/EventTag/Stressor 39 = 116，与 seed 同源）
  - 故障注入暴露并修复**可用性缺陷**：driver 默认 `maxTransactionRetryTime=30s`，图拒连时把复盘链路从 0s 拖到 32s；改为驱动超时 2s + 30s 短路熔断后实测降至 **3s**，Neo4j 恢复即自动半开探测并补同步（`kg graph synced at content version N`）
- **M18 图模式收口（2026-09-29 完成 ✅）**：清偿缺口 G7（图模式压测）/ G8（运行时可观测）/ G9（DB↔图对账）
  - **G8 可观测**：`Neo4jKgService.describe()` 现值快照挂 `GET /admin/metrics/overview` 的 `runtime.kg`（与 `runtime.llmGate` 同处），三态可辨——未启用 `kg=db(kg_node)` / 图可用 `kg=neo4j, circuit=closed, syncedVersion=N` / 图故障 `circuit=open`；该接口刻意不触网，观测动作不产生图库压力（冷态首次读为 `syncedVersion=none`）。另设 `management.health.neo4j.enabled=false`：关掉 Spring 自带的 neo4j 健康项，避免图抖动把实例打成 DOWN（容器会被摘除或重启，与"图不可用不是单点故障"的立场冲突）
  - **G9 对账**：`GET /admin/content/kg/graph-audit` 逐项比对七类节点 + 五类关系（四类受管严格全等；三类闭集只做单向包含——闭集词表权威在 `seed.cypher`，图侧多出的词记 `reserved` 不判错）；图不可达时如实返回 `available=false`，不编造一致结论
  - **G7 压测**：同参数（80 虚拟用户 / think 36s / 5 分钟，LLM 走真 HTTP/SSE 桩）DB 轮 803 请求 · 2.55 rps · p95 246ms，图轮 837 请求 · 2.65 rps · p95 258.9ms，错误率均 0%
  - 真机三态验证抓出并修复一处缺陷（图不可达时对账接口 500/code 2001 → 现返回同构的 `available=false` 降级响应），并**证伪一处误判**：复盘 `PARTIAL_SUCCESS` 源于 LLM 输出校验而非 KG 降级（对照实验：图可用与图不可用在 openai 桩下步骤明细完全相同，mock 口径下为 SUCCESS）

测试基线：H2 回归 **151 例 · 0 失败 · 9 跳过**（32 个测试类；跳过的 9 例均为 `Neo4jKgContractTest`，需真图实例，无实例时按设计跳过，即 142 例实际执行全绿），四条输入路径危机一票拦截各有集成测试；压测 803（DB）/ 837（图）请求均 0% 错误率，明细 `deploy/load_m18_db_results.json`、`deploy/load_m18_graph_results.json`（M10 基线见 `deploy/load_m10_results.json`）。技术债 8 条全部清偿（手册下篇九）。
