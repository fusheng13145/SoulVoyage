# 心屿漫行 SoulVoyage

> 面向大学生群体的任务驱动型多智能体协同心理自助成长平台。
> 仅为自助辅助工具，不做心理诊断、不替代心理咨询师；高危信号触发转介提示。

## 文档

- [项目手册 · 唯一事实源（集成版 v2.1）](docs/项目手册.md) —— 上篇系统设计（架构、调度中心、六大 Agent、数据层、API、安全）＋ 下篇产品深化设计 V2（功能扩展体系、iOS 设计系统、M5–M14 路线图与上线门禁，随各里程碑持续更新实现注记）＋ 附录 A 原始定稿存档 ＋ 附录 B 手册治理协议。原《定稿说明》《产品深化设计》两份文档已并入本手册并退役。
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
| Neo4j | 5.26.12 @127.0.0.1:7687（`D:/neo4j-community-5.26.12`） | 本机实例已就绪并导入 `deploy/neo4j/seed.cypher`（116 节点 / 178 关系，与 `resources/kg/*.json` 同源）；启动与导种子走 `deploy/tools/neo4j.sh start\|seed\|status`（官方 `neo4j.bat` 在本机 PowerShell 5.1 下有 hashtable 键冲突缺陷，脚本内已注释 Java 直调绕过方式）。`SV_NEO4J_ENABLED=false` 时 KG 仍走 DB 词条表 `kg_node`；置 `true` 待后端 driver 实现合入 |
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

`deploy/docker-compose.yml` 已编排 mysql/redis/app/web 四服务（Neo4j 暂不编排，见 compose 内注释——待应用侧接入 driver 后一并补回）；切换时仅需：
1. `docker compose --env-file local.env up -d --build`，应用 datasource/redis 主机名指向容器（`SV_MYSQL_HOST=mysql` 等，生产必须显式选 `SV_LLM_PROVIDER`，禁默认 mock）；
2. 数据卷挂载 `deploy/{mysql,redis}-data/`（已在 .gitignore 排除）；
3. 密钥经 local.env / compose env 注入 `SV_MASTER_KEY`、`SV_JWT_SECRET`、LLM Key。

## 里程碑

**M0–M14 全部完成 ✅**（各里程碑范围与验收明细以[项目手册 §10.2 / §11.3](docs/项目手册.md)为准）：

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

测试基线：H2 回归 138/138（30 个测试类），四条输入路径危机一票拦截各有集成测试；压测 816 请求 0% 错误率，明细 `deploy/load_m10_results.json`。技术债 8 条全部清偿（手册下篇九）。
