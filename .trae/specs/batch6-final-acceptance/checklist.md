# Batch 6 · G9/G10 最终验收清单

> 依据：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 与 `checklist.md` G9/G10。
> 结论日期：2026-11-12。本轮只做验收整理，不新增业务代码。
> 判定口径：**G9 = 工程交付 + 上线门禁**；H-07 的工程侧已完成，真实密钥/域名/证书/AppID 属外部输入。

## 0. 总结论

| 项 | 结论 | 说明 |
|---|---|---|
| H-01–H-07 工程交付 | 7/7 完成 | 每个 H 项均有独立 spec/tasks/checklist 与提交 |
| G9 | **待最终勾选** | 工程项全过；H-07 发布门禁需真实外部输入后 `check_release.mjs` 退出码 0 |
| G10 | **通过** | 后端/前端/小程序全绿；15 个 golden 字段路径一致；C 类零改动 |
| H2 全部 G 项通过 | **阻塞** | 依赖 G9 最终勾选 |

## 1. G9 逐项验收

| ID | 交付 | 提交 | 关键证据 | 状态 |
|---|---|---|---|---|
| H-01 | 任务中心 2.0：步骤时间线、失败重试、Web 任务页 | `0d8b7f5` | 后端 438 例基线通过；新增步骤/重试端点；任务页可用 | PASS |
| H-02 | 情绪洞察 2.0：确定性周摘要、波动、压力源/误区解释、下一步 | `4f57ed6` | 洞察端点；危机态只给支持资源；无诊断话术 | PASS |
| H-03 | 成长计划自适应：顺延/减量/替换/今天不做 | `6777482` | 6 个自适应用例；无 schema 变更；危机态不主动建议 | PASS |
| H-04 | 内容运营闭环：草稿→预览→发布→回滚 + schema 校验 | `aa0a34e` | V3 迁移；12 个新增测试；管理端编辑器/历史回滚；冲突 2003 | PASS |
| H-05 | 可观测与成本护栏：失败告警/预算/抽检/慢请求 TopN | `51e3705` | guardrails 端点；9 个新增测试；管理端四块卡片 | PASS |
| H-06 | 无障碍与键盘：skip link/焦点陷阱/快捷键/对比度 | `aec999b` | 4 个 focusTrap 用例；浏览器键盘走查 PASS；对比度 AA | PASS |
| H-07 | M15 上线收口：Neo4j/HTTPS/生产加固/发布门禁 | `78f7d5f` | compose config PASS；mp-weixin 注入验证；示例 env 预期 FAIL | **CONDITIONAL** |

H-07 条件：真实 LLM/ASR/WX 密钥、备案域名、TLS 证书、小程序 AppID 由运维填入后：

```bash
node deploy/tools/check_release.mjs \
  --env deploy/local.env \
  --miniprogram-env miniprogram/.env.production \
  --tls-dir deploy/certs
# 期望：结果: N PASS / 0 FAIL；退出码 0
```

并在部署机执行：

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.tls.yml \
  --env-file deploy/local.env config
docker run --rm -v "$PWD/deploy/nginx.https.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t   # 需先挂载有效证书到 /etc/nginx/certs
```

> 当前环境 Docker daemon 未运行，`nginx -t` 未执行；compose YAML 已用最小 env 验证 PASS。

## 2. G9 横切门禁

- [x] 每个 H 项均有独立变更单与落地记录（`.trae/specs/batch6-h0*`）。
- [x] 合同变更均为纯新增；前端/小程序 `gen:api` 已同步。
- [x] H-04 新增 Flyway V3，可前滚；未改历史迁移与 C-06 `schema.sql`。
- [x] C 类文件零改动（`check_c_guard.mjs` 扫描 12 个变更路径，清单 15 条）。
- [x] H-07 的 HTTP 默认 compose 未被 TLS override 破坏。
- [ ] H-07 真实外部输入与 `check_release` 0 FAIL（阻塞 G9 勾选）。
- [ ] 部署机 HTTPS 冒烟（301/HSTS/CSP/SSE/健康检查）与小程序真机联调（上线前执行）。

## 3. G10 最终回归矩阵

| 范围 | 命令 | 期望 | 最近结果 | 状态 |
|---|---|---|---|---|
| 后端全量 | `backend: mvn -q test` | 0 失败 | 470 例 / 0 失败 / 9 跳过 | PASS |
| 后端打包 | `backend: mvn -q -DskipTests package` | BUILD SUCCESS | 通过 | PASS |
| 前端单测 | `frontend: npm run test` | 全绿 | 116 例 / 12 文件 | PASS |
| 前端类型 | `frontend: npx vue-tsc --noEmit` | 0 错误 | 通过 | PASS |
| 前端构建 | `frontend: npm run build` | 构建成功 | 通过 | PASS |
| 小程序单测 | `miniprogram: npm run test` | 全绿 | 20 例 | PASS |
| 小程序类型 | `miniprogram: npm run typecheck` | 0 错误 | 通过 | PASS |
| 小程序 lint | `miniprogram: npm run lint` | 0 错误 | 通过 | PASS |
| 小程序构建 | `miniprogram: npm run build:mp-weixin` | 构建成功 | 通过（无 AppID 警告为预期） | PASS |
| 契约 | `backend: mvn -q test -Dtest=OpenApiContractTest` | 快照一致 | 通过 | PASS |
| 契约生成 | `frontend/miniprogram: npm run gen:api` | 无未提交 diff | 已同步 | PASS |
| golden | `node deploy/tools/snapshot_batch0.mjs` + diff | 15 文件字段路径一致 | 15/15 一致 | PASS |
| C guard | `node deploy/tools/check_c_guard.mjs` | PASS | PASS | PASS |
| compose | `docker compose ... config` | 配置可解析 | PASS（最小 env） | PASS |
| 发布门禁 | `node deploy/tools/check_release.mjs ...` | 0 FAIL | 示例 env 17 FAIL（预期） | **PENDING** |
| nginx 语法 | `nginx -t` | syntax is ok | 未执行（Docker daemon 停止） | **PENDING** |

G10 判定：**工程回归通过**；两项 PENDING 属上线环境执行项，不影响代码回归结论。

## 4. 上线前必须补齐（G9 → PASS）

1. `deploy/local.env` 填真实 LLM/ASR/WX/Neo4j/主密钥/JWT 密钥，且 `SV_LLM_PROVIDER` / `SV_ASR_PROVIDER` 非 mock、`SV_WX_PROVIDER=http`。
2. `deploy/certs/fullchain.pem`、`privkey.pem` 放有效证书；`SV_DOMAIN` 与 `SV_CORS_ORIGINS` 为备案 HTTPS 域名。
3. `miniprogram/.env.production` 填 `VITE_ONLINE_ORIGIN`，微信后台加入 request 合法域名，`manifest.json` 填 AppID。
4. 启动 Docker daemon，执行 `check_release.mjs` 至 0 FAIL。
5. 部署机执行 compose config + nginx -t + 启动，冒烟：`https://域名/`、`/actuator/health`、SSE 任务流。
6. 小程序真机登录/日记/任务流联调；确认无 mock 回退。
7. 验收人签字后勾选 G9/H2。

## 5. 最终判定

- [x] G10 工程回归通过。
- [ ] G9 最终通过（等待第 4 节 1–7）。
- [ ] H2 全部 G 项通过（依赖 G9）。
- [ ] H3/H4/H5 最终确认（建议随 G9 一并签字）。
