# H-07 Checklist

- [x] A1 compose 含 Neo4j 服务与健康检查，数据卷与端口仅本机
- [x] A2 app 注入 ASR/WX/Neo4j/LLM budget/业务时区，生产禁 mock
- [x] A3 app 对 Neo4j 只依赖 service_started，图故障不阻塞启动
- [x] A4 `local.env.example` 覆盖全部发布变量且无真实密钥
- [x] B1 HTTPS nginx：80→443、TLS1.2/1.3、HSTS、CSP、X-Frame、nosniff、Referrer-Policy、Permissions-Policy
- [x] B2 SSE 仍不缓冲，静态缓存与 index 回源正确
- [x] B3 TLS override 可独立启用，默认 HTTP compose 不被破坏
- [x] C1 `server.forward-headers-strategy=framework`
- [x] C2 生产错误响应不含 message/stacktrace/exception
- [x] C3 Dockerfile 以非 root 用户运行
- [x] D1 小程序线上域名来自 `VITE_ONLINE_ORIGIN`，无硬编码正式域名
- [x] D2 `env.d.ts`/`.env.example` 就绪，typecheck + mp-weixin build 通过（注入已验证）
- [x] E1 `check_release.mjs` 校验密钥/域名/证书/小程序域名，输出不含密钥
- [ ] E2 示例 env 预期 FAIL 已验证；真实 env 填齐后 PASS（待运维提供真实值）
- [x] F1 后端 470 例；前端 116 例 + typecheck；小程序 test/typecheck/lint + build
- [x] F2 golden 15 一致；C guard PASS
- [ ] F3 外部依赖（真实密钥/备案域名/证书/AppID）由运维填入后再上线
