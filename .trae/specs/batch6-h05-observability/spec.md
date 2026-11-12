# H-05 可观测与成本护栏变更单

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 H-05；基线 tasks.md Task 11.5。
> 性质：扩展功能（运营可观测）。**只读聚合**：不新增外部依赖、不写业务表、不改任务/LLM/风险语义。
> 范围：后端指标服务 + 管理端总览页；小程序/用户端不新增入口。

## 1. 目标

把「出问题后才发现」变成「运行时看得见」：

| 能力 | 交付 |
|---|---|
| 任务失败告警 | 24h 失败数/失败率/最近失败任务，分 OK/WARN/CRITICAL |
| LLM 成本预算 | 24h token 用量、估算成本、预算占用百分比与超限状态 |
| 内容质量抽检 | 近 7 天报告抽样（不解密正文）：好评/不确定/差评与差评率 |
| 慢请求 TopN | 近 24h ≥阈值的请求，按耗时倒序 TopN |

红线：聚合只读；不引入 Prometheus/外部 APM；慢请求仅内存环形缓冲，重启清零；不记录请求体/查询串/用户身份。

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `AdminMetricsController` | 已有 overview（任务/风险/Agent/Token/反馈/运行时），直接依赖 4 个 Repository |
| `task_step_log` | 已有 `tokens_in/tokens_out/llm_calls/cost_ms/created_at`，可按窗聚合 |
| `task_instance` | 已有 status/errorMsg/createdAt，可算失败率与最近失败 |
| `report` | 已有 feedback（USEFUL/UNSURE/UNHELPFUL）与标题/类型/风险级别，可抽样且不碰正文 |
| 慢请求 | 无记录；Micrometer 只有聚合计数，无法还原 TopN |
| `AdminHomeView` | 已有总览，无护栏区 |

## 3. 改动

### 3.1 后端（无 schema 变更）

**重构**：把 `AdminMetricsController` 的聚合逻辑下沉到 `AdminMetricsService`，Controller 只保留路由（消除 Controller→Repository 的冻结违规）。

**新增 `SlowRequestRecorder` + `SlowRequestFilter`**：

- 仅记录 `/api/v1/` 请求（排除 `/actuator` 与护栏本身），不记录 query、body、用户身份。
- 仅保留 `duration ≥ soulvoyage.observability.slow-request-ms`（默认 500ms）的请求；内存环形缓冲上限 500 条，重启清零。
- 记录字段：method、URI path、status、durationMs、at。

**新增 `GET /api/v1/admin/metrics/guardrails`**：

```text
generatedAt
failureAlert: { level, message, failed, finished, failureRate, recent[] }
costBudget: { windowHours, tokensIn, tokensOut, totalTokens, tokenBudget,
              estimatedCost, costBudget, currency, usedPercent, level, message }
qualitySample: { sampled, rated, useful, unsure, unhelpful, unhelpfulRate,
                 level, message, items[] }
slowRequests: [{ method, path, status, durationMs, at }]
```

确定性阈值：

- 失败告警：窗口 24h；`FAILED / (SUCCESS+PARTIAL_SUCCESS+FAILED)`；失败率 ≥20% 且失败 ≥3 → `CRITICAL`；失败率 ≥10% 或失败 ≥1 → `WARN`；否则 `OK`。最近失败取 5 条（taskNo/pipeline/error/createdAt）。
- 成本预算：窗口 24h；`tokenBudget` 默认 200 万；输入价 0.001/1k、输出价 0.002/1k、日成本预算 10（币种 CNY）；占用率取 token% 与 cost% 的较大值；≥100% → `OVER`，≥80% → `WARN`，否则 `OK`。
- 质量抽检：近 7 天最新 20 份报告；只读 `feedback`，不解密正文；已评 <5 → `INSUFFICIENT`；差评率 ≥30% → `WARN`；否则 `OK`。条目最多 10 条，差评优先。
- 慢请求：近 24h，`top` 默认 10（钳制 1..50），按 duration 倒序。

配置（`application.yml`）：

```yaml
soulvoyage:
  llm:
    budget:
      daily-tokens: ${SV_LLM_DAILY_TOKENS:2000000}
      price-in-per-1k: ${SV_LLM_PRICE_IN:0.001}
      price-out-per-1k: ${SV_LLM_PRICE_OUT:0.002}
      daily-cost: ${SV_LLM_DAILY_COST:10.0}
      currency: ${SV_LLM_BUDGET_CURRENCY:CNY}
      alert-percent: ${SV_LLM_BUDGET_ALERT_PERCENT:80}
  observability:
    slow-request-ms: ${SV_SLOW_REQUEST_MS:500}
```

### 3.2 管理端

`AdminHomeView.vue` 在现有总览下方新增四块：

- 失败告警卡：等级/失败率/最近失败列表。
- 成本预算卡：进度条、token/成本、状态文案。
- 质量抽检卡：样本量、差评率、条目（差评高亮）。
- 慢请求 TopN 卡：method/path/status/耗时/时间。
- 护栏接口失败不阻断 overview；各自独立展示。

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 告警 | FAILED 任务进入最近列表；0 失败为 OK；阈值边界可复现 |
| 预算 | token/成本估算与占用率正确；超 80%/100% 分级正确 |
| 抽检 | 只读 feedback/标题，不出现报告正文；差评率计算正确 |
| 慢请求 | ≥阈值才记录；排除 actuator/query；TopN 排序正确；缓冲有上限 |
| 权限 | 仅 ADMIN + admin:task 可读；无写操作 |
| 契约 | OpenAPI 纯新增 guardrails 与 DTO；两端生成类型同步 |
| 前端 | 总览页四块可渲染空态/正常态；112+ 例、typecheck、build 绿 |
| 回归 | 后端全量绿；golden 15 场景不变；C guard PASS |
| 回滚 | 纯新增服务/端点/页面卡片；回滚删除即可，无数据迁移 |

## 5. 批准状态

- [x] 用户「提交变更并继续 H-05」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 后端 | `AdminMetricsService` 承接 overview 与 guardrails；`AdminMetricsController` 仅依赖 service（Repository 冻结违规收敛） |
| 慢请求 | `SlowRequestRecorder`（500 条环形缓冲）+ `SlowRequestFilter`（仅 /api/v1，排除 actuator/护栏自身，不读 query/body）；启动日志已确认过滤器注册 |
| 预算配置 | `soulvoyage.llm.budget.*` 与 `soulvoyage.observability.slow-request-ms` 已写入 `application.yml` |
| 契约 | OpenAPI 纯新增 250 行（guardrails 端点 + 7 DTO）；前端/小程序生成类型已同步 |
| 管理端 | `AdminHomeView` 新增失败告警、成本预算、质量抽检、慢请求 TopN 四块 |
| 测试 | 后端 470 例 0 失败（9 跳过）；前端 112 例 + typecheck + build；小程序 20 例 + typecheck + lint |
| 回归 | golden 15 场景字段路径一致；C guard PASS（C 类零改动）；采样噪声已还原 |
| 说明 | 聚合只读、不解密报告正文、不记录请求体/身份；无 schema 变更、无新增外部依赖 |
