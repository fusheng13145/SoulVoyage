# H-02 情绪洞察 2.0 变更单

> 上位基准：`.trae/specs/establish-soul-voyage-baseline/spec.md` §4.2 H-02；基线 tasks.md Task 11.2。
> 性质：扩展功能（非重构）。**只读消费** `emotion_profile`、`emotion_trajectory` 与内容知识库；不新增风险语义，不改危机状态机。
> 范围：后端新增确定性洞察接口 + Web 洞察页增强；小程序本项不做（端侧暂无洞察页，接口保留复用能力）。

## 1. 目标

在现有情绪曲线/周画像之上补齐四件事：

| 能力 | 交付 |
|---|---|
| 周报自动摘要 | 每週一条确定性摘要（记录量、效价环比、主要压力源、误区线索） |
| 波动提示 | 近 14 天效价标准差/极值/最大单日变化 + 中性提示语 |
| 压力源/误区解释 | 压力源释义与微行动；认知误区定义、典型句式、苏格拉底提问 |
| 可执行的下一步 | 按优先级生成 1–3 个动作，直达既有页面 |

红线：**规则先行，LLM 摘要本项不接入**；文案只做描述与自助建议，不输出任何诊断结论；危机态只呈现支持资源。

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `EmotionController` | 只有 `/trajectory`、`/profile`、`/self-rating`；无摘要/波动/解释/下一步接口 |
| `emotion_profile` | 已有 `avg_valence`、`risk_level`、`stressor_top_json`、`distortion_top_json`，可直接聚合 |
| `emotion_trajectory` | 已有逐日效价/强度/情绪词，可算波动与记录覆盖 |
| `ContentStore` | 已缓存认知误区定义（definition/typicalSignature/socraticTemplates）与练习目录；无压力源释义 |
| `kg/cognitive_distortions.json` | 已是种子源，含 `stressorMap`（标签→压力源闭集） |
| `InsightsView.vue` | 已有曲线、压力源环图、误区趋势、周画像列表；无摘要/波动/解释/下一步 |

## 3. 改动

### 3.1 内容源（确定性释义）

`kg/cognitive_distortions.json` 增加 `stressorDefinitions`（10 个闭集压力源的 `what` 释义 + `tip` 微行动），文案中性、非诊断。
`ContentDataSeeder`：把 `kg:stressor_definitions` 写入 `content_meta`；**同时修复既有库早退问题**——节点已存在时仍补种缺失的 meta。
`ContentStore`：Snapshot 增加 `Map<String, StressorEntry> stressorDefinitions`，新增只读访问器；JSON 解析失败降级为空 Map。

### 3.2 后端接口

`GET /api/v1/emotions/insights?weeks=4`（登录态 + JWT 属主；`weeks` 钳制 1..12）

新增 `EmotionInsightService`（`domain/emotion`），只读依赖：

| 数据 | 用途 |
|---|---|
| `EmotionProfileRepository.findByUserIdOrderByStatWeekDesc(userId, PageRequest)` | 周维度摘要、压力源/误区聚合 |
| `EmotionTrajectoryRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc` | 记录覆盖、波动计算 |
| `UserRepository` | 只读 `crisisState` 判定关怀态 |
| `ContentStore` | 误区解释、压力源释义、练习/内容可用性 |
| `BusinessCalendar` | 业务日边界 |

响应 `InsightResp`：

```text
generatedFor: String
weeks: int
crisisMode: boolean
weekly: [{
  statWeek, headline,
  lines: [String]            // 记录量 / 效价环比 / 压力源 / 误区线索
}]
volatility: {
  level: STEADY|WAVY|VOLATILE|INSUFFICIENT,
  label, hint,
  sampleDays, stdDev, minValence, maxValence, maxDailyDelta
}
stressors: [{ name, count, what, tip }]
distortions: [{ kgNodeId, name, count, definition, typicalSignature, question }]
actions: [{ code, title, detail, route }]
```

确定性规则：

- 波动窗口固定近 14 天（含今日）；按日取效价均值；样本日 < 5 → `INSUFFICIENT`；标准差 < 0.15 `STEADY`、< 0.30 `WAVY`、否则 `VOLATILE`。最大单日变化只比较间隔 ≤ 3 天的相邻记录日。
- 周摘要按 ISO 周聚合；有周画像时用画像，缺画像但有轨迹的周用轨迹合成摘要；效价环比阈值 0.05；压力源/误区按近 `weeks` 周画像频次求和，排序 `count desc, name asc`，各取前 3。
- 误区解释按名称匹配 `ContentStore.distortions()`；找不到时只给中性兜底句，不臆造定义。
- 下一步优先级（至多 3 条）：误区线索 → 压力源 → 波动偏高 → 近 7 天无记录 → 常规打卡；危机态只返回 `CARE_RESOURCES`（`/crisis`）。
- 文案禁用诊断词（抑郁/焦虑症/PTSD/诊断等），只描述数据与自助动作。

### 3.3 Web

| 文件 | 改动 |
|---|---|
| `api/domains/emotion.ts` | 新增 `insight({ weeks })` |
| `types/domain.ts` | 新增 `EmotionInsightView` 及子类型 |
| `views/InsightsView.vue` | 顶部新增「本周小结」「波动提示」；新增「压力源与误区解释」「下一步」卡片；周画像列表附摘要标题；接口失败不影响既有图表 |

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 接口 | 属主可读；`weeks` 越界被钳制；无记录时返回空摘要/INSUFFICIENT 而非报错 |
| 规则 | 平稳/波动/样本不足三档可复现；危机态只返回支持资源动作；文案无诊断词 |
| 契约 | OpenAPI 快照按预期更新；前端/小程序 `gen:api` 同步零意外 diff |
| 前端 | 新卡片在无数据/有数据/危机态下均可渲染；112+ 例、typecheck、build 全绿 |
| 回归 | 后端全量绿；golden 15 场景字段路径一致；C guard PASS（C 类零改动） |
| 回滚 | 纯新增接口与页面卡片；回滚删除端点/卡片即可，无数据迁移 |

## 5. 批准状态

- [x] 用户「提交变更并继续 H-02」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 后端 | 新增 `EmotionInsightService` 与 `GET /emotions/insights`；查询/打卡下沉 `EmotionService`，`EmotionController` 不再依赖 Repository（ArchUnit 冻结清单同步收敛） |
| 内容 | `stressorDefinitions` 覆盖 10 个压力源闭集；Seeder 对既有库补种 `kg:stressor_definitions`；`ContentStore` 快照暴露只读释义 |
| 契约 | OpenAPI 纯新增 194 行（1 端点 + 7 schema）；前端/小程序生成类型已同步 |
| Web | `InsightsView.vue` 新增本周小结、波动提示、压力源/误区解释、下一步；周画像列表附摘要标题；洞察接口失败不影响既有图表 |
| 测试 | 后端 443 例 0 失败（9 跳过）；前端 112 例 + typecheck + build；小程序 20 例 + typecheck + lint |
| 回归 | golden 15 场景字段路径一致；C guard PASS（C 类零改动）；golden 采样噪声（total/costMs/随机组名）已还原，不进入基线 |
| 说明 | 本次未接入 LLM 摘要；`RiskRules`、危机状态机与审计/加密代码零改动 |
