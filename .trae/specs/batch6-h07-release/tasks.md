# H-07 Tasks

- [x] Task 1: 编排与 Neo4j
  - [x] 1.1 `docker-compose.yml` 增加 Neo4j 服务与健康检查
  - [x] 1.2 app 注入 ASR/WX/Neo4j/LLM budget/业务时区变量
  - [x] 1.3 `local.env.example` 补全发布变量与说明

- [x] Task 2: HTTPS 与安全头
  - [x] 2.1 新增 `nginx.https.conf`（301/HSTS/CSP/安全头/SSE）
  - [x] 2.2 新增 `docker-compose.tls.yml` 覆盖 web 端口与证书挂载

- [x] Task 3: 后端生产加固
  - [x] 3.1 `application.yml` 转发头策略 + 关闭错误详情
  - [x] 3.2 `soul-backend/Dockerfile` 非 root 运行

- [x] Task 4: 小程序线上域名
  - [x] 4.1 `config.ts` 读取 `VITE_ONLINE_ORIGIN`
  - [x] 4.2 `env.d.ts` + `.env.example`

- [x] Task 5: 发布门禁
  - [x] 5.1 新增 `deploy/tools/check_release.mjs`
  - [x] 5.2 示例 env 预期 FAIL；真实 env 待运维填齐后 PASS

- [x] Task 6: 门禁与收口
  - [x] 6.1 `docker compose config` PASS；`nginx -t` 受 Docker daemon 未运行限制
  - [x] 6.2 后端/前端/小程序测试与构建；C guard PASS
  - [x] 6.3 更新基线 tasks.md 11.7 与本变更单落地记录

# Task Dependencies

- Task 2 依赖 Task 1（变量名一致）。
- Task 5 依赖 Task 1–4。
- Task 6 依赖全部。
