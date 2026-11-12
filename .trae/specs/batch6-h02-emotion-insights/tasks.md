# H-02 Tasks

- [x] Task 1: 内容源与缓存
  - [x] 1.1 `kg/cognitive_distortions.json` 新增 `stressorDefinitions`（what/tip）
  - [x] 1.2 `ContentDataSeeder` 补种 `kg:stressor_definitions`，并修复节点已存在时的早退
  - [x] 1.3 `ContentStore` Snapshot 增加压力源释义并暴露只读访问器

- [x] Task 2: 后端确定性洞察
  - [x] 2.1 `EmotionDtos` 新增 `InsightResp` 及子 DTO
  - [x] 2.2 新增 `EmotionInsightService`：周摘要、波动、解释、下一步
  - [x] 2.3 `EmotionController` 增加 `GET /emotions/insights`；查询/打卡下沉 `EmotionService`
  - [x] 2.4 新增 `EmotionInsightTest`（平稳/波动/样本不足/危机态/参数钳制/无诊断词）

- [x] Task 3: 契约更新
  - [x] 3.1 更新 OpenAPI 快照并审阅 diff（纯新增 194 行）
  - [x] 3.2 前端/小程序 `npm run gen:api` 同步类型

- [x] Task 4: Web 洞察页
  - [x] 4.1 `emotionApi.insight` + `types/domain.ts` 视图类型
  - [x] 4.2 `InsightsView.vue` 新增本周小结、波动提示、解释、下一步卡片
  - [x] 4.3 周画像列表附摘要标题；接口失败降级不影响图表

- [x] Task 5: 门禁与收口
  - [x] 5.1 后端 443 例；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
  - [x] 5.2 golden 15 场景字段路径一致、C guard PASS
  - [x] 5.3 更新基线 tasks.md 11.2 与本变更单落地记录

# Task Dependencies

- Task 2 依赖 Task 1（释义与缓存就绪）。
- Task 3 依赖 Task 2（接口形状先定）。
- Task 4 依赖 Task 3（生成类型）。
- Task 5 依赖 Task 1–4。
