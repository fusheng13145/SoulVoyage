# H-03 成长计划自适应变更单

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 H-03；基线 tasks.md Task 11.3。
> 性质：扩展功能（非重构）。**只改计划编排**：不改风险判定、危机状态机、报告/审计/加密语义。
> 范围：后端计划自适应接口 + Web 今日/练习页；小程序本项不做（端侧暂无计划入口）。

## 1. 目标

让计划跟得上人的状态，而不是让人追着计划跑：

| 能力 | 交付 |
|---|---|
| 顺延 | 逾期未完成项移到今天起最近可用日，不记为失败 |
| 减量 | 完成率持续偏低时，把剩余练习压到每天最多 1 项 |
| 替换 | 近 7 天情绪偏低时，把下一项换成更轻的练习 |
| 今天不做 | 一键把今日未完成项顺延到未来，保留计划、不惩罚 |

红线：自适应只由确定性规则触发；不新增诊断/风险判断；危机活跃态不主动建议改编排。

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `PlanService` | 已有物化、今日卡、放弃、跟练回写、到期小结；无任何计划调整能力 |
| `plan_item` | 只有 `scheduled_date / done_at / feedback / version`，无状态列；**本项不新增表/列** |
| `GET /plans/active` | 只返回今日条目；无完成率、逾期数、调整建议 |
| `POST /plans/{id}/drop` | 只有整体放弃；没有"今天不做"的轻量选项 |
| `PracticeView` | 可看全部条目；无调整入口 |
| `TodayView` | 今日计划卡；只有"去跟练" |

## 3. 改动

### 3.1 后端（无 schema 变更）

**计划视图扩展**（`PlanDtos.PlanView` 末尾新增，JSON 纯新增字段）：

```text
adaptation: { type, title, reason, actionLabel } | null
completionRate: int        // 已到期条目完成率 0-100
overdueCount: int          // 逾期未完成数
remainingCount: int        // 未完成总数
nextScheduledDate: String | null   // 最早未完成项日期
todaySkipped: boolean      // 今日无安排但计划仍有未完成项
```

**新增端点**：

| 接口 | 行为 |
|---|---|
| `POST /api/v1/plans/{id}/adapt` | body `{action: POSTPONE\|REDUCE\|REPLACE}`；属主断言；ACTION 且未结束；返回全量 PlanView |
| `POST /api/v1/plans/{id}/skip-today` | 属主断言；把今日未完成项顺延到未来最近可用日；幂等；返回全量 PlanView |

**确定性建议规则**（`PlanService`，按优先级取第一条）：

1. 计划非 ACTION / 已结束 / 无未完成项 / 危机活跃态 → `null`。
2. 存在逾期未完成项 → `POSTPONE`。
3. 已执行 ≥2 天且到期完成率 <40%，且未完成项 >1 → `REDUCE`。
4. 近 7 天有 ≥2 个记录日且平均效价 ≤ -0.30，且存在未完成项 → `REPLACE`。
5. 其余 → `null`。

**应用规则**：

- `POSTPONE`：逾期未完成项按 seq 依次放入从今天起、单日容量 ≤2 的最近可用日；计划 `endDate/days` 随最大排期日重算。
- `SKIP_TODAY`：今日未完成项按 seq 放入从明天起、单日容量 ≤2 的最近可用日；已完成项不动；`endDate/days` 重算。
- `REDUCE`：剩余未完成项（`scheduledDate ≥ 今天`）按日分组，每天保留 seq 最小的一项，删除其余；若每天已只有 1 项，则删除最后一项（至少保留 1 项）；`endDate/days` 收缩到实际剩余范围。
- `REPLACE`：取最早未完成项，换成练习库中时长不高于当前项、且未被计划其他条目占用的最短练习；无候选时保持原样。
- 所有调整只写 `plan_item.scheduled_date` / `exercise_code` / `guidance` 与计划 `endDate/days`，不碰 `items_json` 生成快照（其定位不变）。
- 危机态读取仅用于**抑制建议**；不改状态机、不写风险事件。

### 3.2 Web

| 文件 | 改动 |
|---|---|
| `api/domains/plan.ts` | 新增 `adapt(id, action)`、`skipToday(id)` |
| `types/domain.ts` | 新增 `PlanAdaptationView` 与 PlanView 新字段 |
| `views/TodayView.vue` | 今日计划卡新增「今天不做」；今日已顺延时显示休息提示与下一次日期；有建议时给出跳转练习页的轻提示 |
| `views/PracticeView.vue` | 计划卡新增建议横幅（顺延/减量/替换）与「今天不做」；操作后刷新详情 |

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 规则 | 逾期 → POSTPONE；低完成率 → REDUCE；低情绪 → REPLACE；危机态无建议 |
| 接口 | 属主可调；他人 404；非 ACTION/已结束 400；skip-today 幂等 |
| 编排 | 顺延/减量/替换后 `endDate/days` 与实际排期一致；已完成项不被移动/删除 |
| 契约 | OpenAPI 按预期新增 2 端点与 PlanView 字段；两端 `gen:api` 同步 |
| 前端 | 今日卡可「今天不做」并显示顺延结果；练习页可一键应用建议；112+ 例、typecheck、build 绿 |
| 回归 | 后端全量绿；golden 10-plan 仅新增字段路径且审阅通过；C guard PASS |
| 回滚 | 纯新增端点/字段与页面按钮；回滚删除即可，无数据迁移 |

## 5. 批准状态

- [x] 用户「提交变更并继续 H-03」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 后端 | `PlanService` 新增建议规则、`adapt`、`skipToday`；`PlanController` 新增 `POST /{id}/adapt`、`POST /{id}/skip-today`；无 schema 变更 |
| 契约 | OpenAPI 新增 2 端点、`PlanAdaptationView/AdaptReq` 与 PlanView 6 个字段（其余为 schema 位置重排）；两端生成类型已同步 |
| Web | `TodayView` 新增「今天不做」、顺延休息提示与建议入口；`PracticeView` 新增建议横幅与一键调整 |
| 测试 | 后端 449 例 0 失败（9 跳过）；前端 112 例 + typecheck + build；小程序 20 例 + typecheck + lint |
| 回归 | golden 10-plan 仅新增 12 条字段路径，其余 14 场景字段路径一致；C guard PASS（C 类零改动） |
| 说明 | Golden 采样噪声（admin total/costMs、随机组名）已还原；本项未改 `RiskRules`、危机状态机、审计/加密与报告语义 |
