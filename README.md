# 心屿漫行 SoulVoyage

> 面向大学生群体的任务驱动型多智能体协同心理自助成长平台。
> 仅为自助辅助工具，不做心理诊断、不替代心理咨询师；高危信号触发转介提示。

## 文档

- [项目手册 · 唯一事实源（集成版 v2.1）](docs/项目手册.md) —— 上篇系统设计（架构、调度中心、六大 Agent、数据层、API、安全）＋ 下篇产品深化设计 V2（功能扩展体系、iOS 设计系统、M5–M9 路线图与上线门禁，2026-09-19 定稿）＋ 附录 A 原始定稿存档 ＋ 附录 B 手册治理协议。原《定稿说明》《产品深化设计》两份文档已并入本手册并退役。
- [V2 交互原型](docs/demo/V2交互原型.html) —— 浏览器直接打开（纯前端假数据），M6 视觉与交互基线

## 仓库结构

```
backend/     SpringBoot 3 + Java 21 单体（orchestrator / agent / llm / kg / crypto ...）
frontend/    Vue3 + TypeScript + Vite
deploy/      sql/schema.sql（库表唯一事实源）+ sql/migrate_mN.sql · neo4j/seed.cypher · tools/ · docker-compose.yml（后续引入）
docs/        项目手册.md（唯一事实源）· demo/V2交互原型.html（M6 视觉基线）
```

## 本地开发（当前阶段：无 Docker，全部使用本机服务）

| 依赖 | 本机情况 | 说明 |
|---|---|---|
| JDK | 21 | 虚拟线程已启用 |
| Maven | 3.9 | |
| MySQL | 8.4 @127.0.0.1:3306（服务 MySQL84） | 库 `soulvoyage`，账号 `soulvoyage`，先导入 `deploy/sql/schema.sql` |
| Redis | @127.0.0.1:6379 免密 | 会话上下文 / 任务态 / JWT 黑名单 |
| Neo4j | 暂未安装 | `soulvoyage.neo4j.enabled=false`，KG 走 DB 词条表（`kg_node`，启动时由 `resources/kg/*.json` 播种，与 `deploy/neo4j/seed.cypher` 同源），Neo4j 就绪后换 driver 实现 |
| LLM | 无 Key | `SV_LLM_PROVIDER=mock` 走 MockLlmClient，接口留好 |

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
```

## 后续 Docker 化规划

`deploy/docker-compose.yml` 已预置 mysql/redis/neo4j/app 编排；切换时仅需：
1. `docker compose up -d`，将应用 datasource/redis 主机名指向容器（环境变量 `SV_MYSQL_HOST=mysql` 等）；
2. 数据卷挂载 `deploy/{mysql,redis,neo4j}-data/`（已在 .gitignore 排除）；
3. 密钥经 compose env / secret 注入 `SV_MASTER_KEY`、`SV_JWT_SECRET`、LLM Key。

## 里程碑

M0 骨架+建库+登录 ✅ → M1 调度中心+LLM网关(Mock)+情绪感知 Agent+AgentFlow 可视化 ✅ → M2 溯源推理+知识图谱约束+复盘报告 ✅ → M3 人际模拟训练（4 场景卡+NPC 导演+复盘评分）✅ → M4 疏导干预+风险双轨研判归档+成长档案导出（限时一次性打印页）✅
