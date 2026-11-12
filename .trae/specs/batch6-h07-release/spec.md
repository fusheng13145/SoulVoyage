# H-07 M15 上线收口变更单

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 H-07；基线 tasks.md Task 11.7。
> 性质：部署/运维/合规收口。**不改业务逻辑、不改接口契约**；不把任何真实密钥写入仓库。
> 范围：`deploy/`、`backend/Dockerfile`、`application.yml`、小程序线上域名配置。

## 1. 目标

把「能本地跑」收敛为「可安全上线」：

| 能力 | 交付 |
|---|---|
| 真实模型/语音/微信 | compose 注入 LLM/ASR/WX 变量，生产禁 mock；缺失即启动失败 |
| HTTPS 与域名 | 独立 TLS nginx 配置 + compose override；HSTS 与安全响应头 |
| 小程序线上域名 | `VITE_ONLINE_ORIGIN` 注入，移除硬编码占位；构建前校验 |
| Neo4j 编排 | compose 增加 Neo4j 服务与健康检查；图不可用仍回落 `kg_node` |
| 发布门禁 | `check_release.mjs` 校验密钥/域名/证书/小程序域名，不打印密钥 |

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `deploy/docker-compose.yml` | 只有 MySQL/Redis/App/Web；无 Neo4j；LLM 必填但 ASR/WX/Neo4j 未编排 |
| `deploy/nginx.conf` | 仅 HTTP；无 HSTS/CSP/X-Frame 等安全头；无 TLS |
| `backend/Dockerfile` | 运行层以 root 启动 |
| `application.yml` | 未显式关闭错误详情、未声明反向代理头策略 |
| `miniprogram/src/config.ts` | 线上域名硬编码 `REPLACE-WITH-BEIAN-DOMAIN` |
| 发布校验 | 无脚本；密钥/域名/证书是否就绪只能靠人工核对 |

## 3. 改动

### 3.1 反向代理与 HTTPS

- `deploy/nginx.https.conf`（新增）：80 端口 301 到 HTTPS；443 启用 TLS1.2/1.3；保留 SSE 反代与静态缓存；补安全头：
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Permissions-Policy: camera=(), microphone=(self), geolocation=()`
  - 保守 CSP：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; media-src 'self' blob:`
- `deploy/docker-compose.tls.yml`（新增）：覆盖 `web` 的 80/443 端口、TLS nginx 与 `${SV_TLS_DIR:-./certs}` 证书挂载。
- `deploy/nginx.conf` 保持本机 HTTP 用法，避免没有证书时 compose 起不来。

### 3.2 编排补齐

`deploy/docker-compose.yml`：

- 增加 `neo4j:5.26-community`：`NEO4J_AUTH`、数据卷、7474/7687 仅本机、`cypher-shell` 健康检查。
- `app` 增加 `SV_ASR_*`、`SV_WX_*`、`SV_NEO4J_*`、`SV_LLM_BASE_URL/MODEL`、LLM 预算、`SV_BUSINESS_ZONE`、`SV_SLOW_REQUEST_MS` 注入。
- `app` 对 neo4j 使用 `service_started`（不是 healthy）：图故障不阻塞应用，KG 自动回落 `kg_node`。
- MySQL/Redis/Neo4j 端口继续只绑 `127.0.0.1`；公网只经 web。

### 3.3 后端生产加固

- `application.yml`：
  - `server.forward-headers-strategy: framework`（信任 nginx 的 `X-Forwarded-Proto`）
  - `server.error.include-message: never`、`include-stacktrace: never`、`include-exception: false`
  - `management.endpoints.web.exposure.include` 保持现状；公网只反代 `/actuator/health`
- `backend/Dockerfile`：新增非 root 用户运行（`app`），jar 以 `--chown` 复制；健康检查不变。

### 3.4 小程序线上域名

- `miniprogram/src/config.ts`：`ONLINE_ORIGIN` 改为读取 `import.meta.env.VITE_ONLINE_ORIGIN`，去掉硬编码占位；缺省值仍为占位并在发布门禁中被拦截。
- 新增 `miniprogram/src/env.d.ts` 与 `miniprogram/.env.example`。
- `miniprogram/src/manifest.json` 的 `appid` 由发布流程注入/填写（空 appid 会在微信开发者工具中提示；仓库不写真实 AppID）。

### 3.5 发布门禁

新增 `deploy/tools/check_release.mjs`：

- 入参：`--env`（默认 `deploy/local.env`）、`--miniprogram-env`（默认 `miniprogram/.env.production`）、`--tls-dir`（默认 `SV_TLS_DIR`）。
- 校验：LLM/ASR 非 mock 且密钥非占位；WX 为 http 且 AppID/Secret 就绪；主密钥 base64 32 字节；JWT 密钥 ≥32；CORS 全为 https 且包含域名；Neo4j 启用且密码就绪；证书 `fullchain.pem`/`privkey.pem` 存在；小程序线上域名为 https 且非占位。
- 只输出 `PASS/FAIL + 变量名`，不打印值；失败退出码 1。

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 编排 | `docker compose ... config` 通过；Neo4j 服务定义完整；app 依赖不阻塞图故障 |
| TLS | `nginx -t` 通过；HTTP 301；HSTS/安全头齐全；SSE 不缓冲 |
| 后端 | Dockerfile 非 root；错误详情关闭；转发头策略生效；后端全量测试绿 |
| 小程序 | `VITE_ONLINE_ORIGIN` 注入；typecheck/build 通过；无硬编码线上域名 |
| 发布门禁 | 示例 env 预期 FAIL；真实 env 填齐后 PASS；输出不含密钥 |
| 回归 | 前端/小程序/后端测试绿；golden 15 不变；C guard PASS |
| 外部输入 | 真实 LLM/ASR/WX 密钥、备案域名、TLS 证书由运维提供；本变更单只交付注入位与校验 |

## 5. 批准状态

- [x] 用户「提交 H-06 并继续 H-07」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 编排 | compose 增加 Neo4j 5.26 + `cypher-shell` 健康检查；app 注入 ASR/WX/Neo4j/预算/时区/慢请求变量；`docker compose config`（含 TLS override）PASS |
| TLS/代理 | 新增 `nginx.https.conf`（80→443、TLS1.2/1.3、HSTS、CSP、X-Frame、nosniff、Referrer-Policy、Permissions-Policy、SSE 不缓冲）与 `docker-compose.tls.yml` |
| 后端加固 | `forward-headers-strategy=framework`；关闭 error message/stacktrace/exception；Dockerfile 改为非 root `app` 用户 |
| 小程序 | `ONLINE_ORIGIN` 改读 `VITE_ONLINE_ORIGIN`；新增 `env.d.ts`/`.env.example`；`build:mp-weixin` 验证注入成功 |
| 门禁 | `check_release.mjs` 对示例 env 预期 FAIL（17 项未就绪）；真实 env 填齐后应 PASS；输出不含密钥 |
| 测试 | 后端 470 例；前端 116 例 + typecheck；小程序 test/typecheck/lint + mp-weixin build；golden 15 一致；C guard PASS |
| 受限项 | Docker daemon 未运行，`nginx -t` 未执行；真实 LLM/ASR/WX 密钥、备案域名、TLS 证书、AppID 由运维提供后跑 `check_release` |
