# Batch 7A · M-07 推荐解释性 + M-03 报告导出 2.0

> 状态：**已实施（代码与自动化门禁通过；打印视觉复核待人工）**
> 批准口径（用户 2026-11-12）：
> - 批准 Batch 7A；
> - M-07 采用**读取时派生理由**，**不新增 `plan_item.reason` 列、不做 V4 迁移**；
> - M-02 边文案接受「相关/适用于」表述（Batch 7B 实施，本批不碰）；
> - M-05 / M-04 等 M-01 / M-02 完成后再逐项走 B 类审批。
> 红线：不做诊断、不替代咨询、不给药物建议；C 类模块零改动；不引入 PDF 库。

## 1. 目标与非目标

### 1.1 目标

| 项 | 目标 |
|---|---|
| M-07 | 计划项在用户端显示**确定性、可解释的理由**（1–2 条短句），说明「为什么推荐这个练习」 |
| M-03 | 单份报告可「打印 / 存为 PDF」；成长档案打印页有目录、章节锚点、分页控制；打印预览不被应用壳截断 |

### 1.2 非目标

- 不新增 `plan_item.reason` 列，不做数据库迁移。
- 不在读取路径调用 LLM，不二次生成文案。
- 不引入 iText / PDFBox 等服务端 PDF 依赖；继续使用浏览器打印。
- 不改计划编排、风险判定、危机态路由；不触碰 C 类模块。
- 不做 M-02 / M-05 / M-04 的内容。

## 2. M-07 推荐解释性

### 2.1 数据来源与派生顺序

读取时只使用**已持久化数据**，一次 `view()` 内不重复解密、不重复查库：

1. `growth_plan.items_json`（生成时快照）中的 `matchedExercises[]`：
   - 按 `exerciseId == plan_item.exercise_code` 命中第一条非空 `reason`；
   - 截断到 80 字，去掉换行；
   - 若包含诊断/疗效类禁用词（`诊断`、`治疗`、`治愈`、`疗效`、`病因`、`抑郁症`、`焦虑症`、`处方`、`药物`），整条丢弃，进入下一级。
2. 练习 `applyEmotions` 与近 7 天情绪轨迹的主导情绪做确定性匹配：
   - 主导情绪 = 近 7 天 `primaryEmotion` 出现次数最多者；次数并列时取最近一条记录的情绪；
   - 类别到中文情绪的匹配表见 §2.2；
   - 命中后输出：`和最近常出现的「{情绪}」感受相关`。
3. 兜底（保证每一项都有理由）：
   - `ex_54321`（grounding）：`此刻先做这一件，把注意力放回身体感受`；
   - 其他：`轻量自助练习，按自己的节奏做完就好`。

最终去重、最多保留 2 条。理由只描述匹配依据，不承诺疗效、不做因果断言。

### 2.2 `applyEmotions` 类别 → 可匹配情绪标签

| 类别 | 可匹配的中文情绪 |
|---|---|
| `ANXIETY` | 焦虑 |
| `FEAR` | 恐惧 |
| `ANGER` | 愤怒 |
| `HIGH_PRESSURE` | 压力、焦虑 |
| `SADNESS` | 悲伤、委屈 |
| `LOW_MOOD` | 悲伤、疲惫、麻木 |
| `SHAME` | 羞耻、内疚 |
| `GUILT` | 内疚、羞耻 |
| `LONELINESS` | 孤独 |
| `NUMBNESS` | 麻木 |
| `CONFUSED` | 困惑 |

`ACUTE_STRESS` / `INSOMNIA` 不直接映射到单一情绪标签，避免过度解释；它们仍可通过第 1 级 `matchedExercises.reason` 获得理由，否则走兜底。

### 2.3 后端改动

| 文件 | 改动 |
|---|---|
| `soul-backend/src/main/java/com/leyon/soul/domain/plan/PlanService.java` | 新增 `RecentEmotion` 记录、`recentEmotion()`、`snapshot()`、`reasonsFor()`、`matchedReason()`、`matchedEmotionLabel()`、`safeReason()`；`view()` 为每项输出 `reasons`；`adaptation()` 复用同一次近 7 天查询结果，去掉重复 `recentValence()` 查询 |
| `soul-backend/src/main/java/com/leyon/soul/domain/plan/dto/PlanDtos.java` | `PlanItemView` 末尾新增 `List<String> reasons` |
| `soul-backend/src/main/java/com/leyon/soul/domain/plan/PlanController.java` | `item()` 映射 `reasons`，空值归一为 `List.of()` |

实现约束：
- `view()` 内只解析一次 `itemsJson`，只调用一次 `trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc()`。
- `list()` 对同一用户的多个计划复用同一份 `RecentEmotion`，避免 N 次轨迹查询。
- 不读取/解密 `report` 表；M-07 仅依赖 `items_json` 与 `emotion_trajectory`。
- `reasons` 对所有计划项恒非空。

### 2.4 前端改动

| 文件 | 改动 |
|---|---|
| `soul-frontend/src/types/domain.ts` | `PlanItemView` 新增 `reasons: string[]` |
| `soul-frontend/src/views/PracticeView.vue` | 计划项在 guidance 下新增「为什么是它」行，展示 `reasons.join('；')` |
| `soul-frontend/src/views/TodayView.vue` | 今日计划卡展示 `reasons[0]`（空间受限，只显示第一条） |
| `miniprogram/src/api/app.ts` | `PlanItem` 新增 `reasons?: string[]` |
| `miniprogram/src/pages/today/index.vue` | guidance 下展示 `reasons[0]` |

样式要求：使用现有 `sv-muted` / caption 字号；不新增卡片层级；不改变「去跟练」按钮位置；空理由不渲染空行。

### 2.5 验收

- 每个计划项都有 1–2 条理由；无报告、无情绪记录、危机 grounding 场景也有稳定兜底。
- 同一 `items_json` + 同一轨迹输入，多次读取理由完全一致。
- 读取路径无 LLM 调用、无报告解密、无新增列。
- 理由文案不含诊断/疗效词；不出现「治疗」「治愈」「病因」等表述。
- `GET /plans/active`、`GET /plans`、`GET /plans/{id}`、`POST /plans/{id}/adapt`、`POST /plans/{id}/skip-today` 的 `items[].reasons` 均存在。

## 3. M-03 报告导出 2.0

### 3.1 单报告打印

`soul-frontend/src/views/ReportDetailView.vue`：

- 顶部操作区与页面底部各新增一个「打印 / 存为 PDF」按钮，调用 `window.print()`。
- 新增 print-only 标题块：报告标题、类型、生成时间、风险档；页脚放固定免责声明。
- 打印时隐藏导航、批注/反馈卡（收藏、评价、反馈输入）、按钮；正文卡保留。
- 打印样式：
  - 卡片去掉阴影，保留 1px 浅边框；
  - `break-inside: avoid`，标题与首段不分离；
  - `h2/h3` 后避免分页；
  - 不打印原始 JSON 折叠区。

### 3.2 成长档案目录与分页

`soul-frontend/src/views/ArchiveView.vue`：

- 快照视图新增目录（`nav.toc`）：
  - `#snap-reports` 报告（N 份）
  - `#snap-profiles` 周画像（N 周）
  - `#snap-emotions` 情绪记录（N 点）
- 每个章节用 `<section :id="...">` 包裹；目录使用锚点链接，屏幕可跳转，打印显示为目录页。
- 打印时：
  - 报告章节从标题页后开始；
  - 周画像、情绪记录各自 `break-before: page`；
  - 每章顶部有章节标题；页脚放生成时间与免责声明，不放正文敏感内容。
- 标题页补充：报告数、情绪点数、生成时间。

### 3.3 应用壳打印修复（关键）

`soul-frontend/src/App.vue`：

- 当前 `.canvas` 为 `height: 100dvh; overflow: hidden`，`.screen` 内部滚动；浏览器打印只会输出可视区，长档案会被截断。新增全局 `@media print`：
  - `.stage`、`.canvas`、`main.screen` 恢复 `height: auto; overflow: visible;`，去掉圆角、边框、背景；
  - `body` 允许滚动、白底；
  - 给 `SvTabBar`、`SvToastHost`、`SvDialogHost`、`SvSheet`、`SvCrisisBanner` 加 `class="print-hide"`，打印时隐藏；
  - 跳过链接、`sr-only`、`#sv-layer` 打印隐藏；
  - `@page { size: A4; margin: 16mm 14mm; }`。
- 该改动只影响打印媒体，不影响屏幕布局与 C 类逻辑。

### 3.4 验收

- 单份报告可打印，打印预览包含标题、正文、免责声明，不含收藏/反馈控件。
- 档案打印预览从标题页开始，目录可见，章节分页正确，无截断。
- 目录锚点在屏幕可跳转。
- 导出仍走现有「限时一次性快照 + 审计」链路，未新增后端导出接口。
- 不新增 PDF 依赖。

## 4. 契约与兼容

- `soul-backend/openapi/openapi.json` 仅新增 `PlanItemView.reasons` 字段；无接口增删。
- 重新生成后运行前端与小程序 `npm run gen:api`，两端 `schema.d.ts` 同步。
- 小程序手写 `PlanItem` 类型同步 `reasons?: string[]`。
- 旧客户端忽略新字段；新客户端对缺失 `reasons` 做空数组降级。

## 5. 测试计划

### 5.1 后端

`soul-backend/src/test/java/com/leyon/soul/domain/plan/PlanServiceTest.java` 新增：

1. `items_json.matchedExercises` 命中 → 理由取匹配项，截断 80 字。
2. 匹配理由含禁用词 → 丢弃并走情绪匹配/兜底。
3. 无匹配理由 + 主导情绪与 `applyEmotions` 命中 → 「和最近常出现的「X」感受相关」。
4. 主导情绪并列 → 取最近记录。
5. 危机 grounding（`itemsJson="[]"`、`ex_54321`）→ grounding 兜底句。
6. 无报告、无轨迹、普通练习 → 通用兜底句。
7. 每项 `reasons` 非空、≤2 条、无重复。
8. `list()` 多计划只查一次轨迹仓库（`verify(trajRepo, times(1))`）。
9. `adaptation()` 仍按原逻辑工作，且不额外查询轨迹。

### 5.2 前端 / 小程序

- `npm run typecheck`、`npm run test`、`npm run build`（Web）。
- 小程序 `npm run typecheck`、`npm run test`、`npm run lint`、`npm run build:mp-weixin`。
- 浏览器走查（有可用测试账号时）：
  - 练习页/今日页显示理由；
  - 报告详情出现「打印 / 存为 PDF」；
  - 档案快照目录锚点可跳转；
  - 打印媒体模拟下 `.canvas` 无固定高度、目录与章节存在。

### 5.3 全门禁

- 后端 `mvn -q test`。
- `OpenApiContractTest` 快照更新后通过。
- golden 15：`node deploy/tools/snapshot_batch0.mjs` + diff。
- C guard：`node deploy/tools/check_c_guard.mjs` PASS。
- ArchUnit 分层冻结通过（新增方法不改变构造签名与依赖方向）。

## 6. 回滚

- 代码回滚即可；无数据库迁移、无数据回填。
- `reasons` 为纯新增响应字段，旧前端不受影响。
- 打印样式仅作用于 `@media print`，屏幕端回滚无副作用。

## 7. 审批记录

- 用户已批准 Batch 7A 及 M-07 读取时派生、不新增列的决定。
- M-02 边文案「相关/适用于」已接受，但不在本批实施。
- M-05 / M-04 仍待 M-01 / M-02 完成后逐项 B 类审批。
