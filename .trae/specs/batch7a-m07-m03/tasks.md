# Batch 7A · 实施任务清单

> 依据：`spec.md`。按顺序执行；每完成一项勾选并记录证据。
> 红线：无数据库迁移、无 LLM 读取调用、C 类零改动、不引入 PDF 依赖。

## 1. M-07 后端派生理由

- [ ] 1.1 `PlanService` 新增 `RecentEmotion` 记录与 `recentEmotion(userId, today)`：
  - 近 7 天轨迹一次查询；
  - `avgValence` 保持原口径（少于 2 个记录日返回 null）；
  - `dominantLabel` 取出现次数最多者，并列取最近一条。
- [ ] 1.2 `PlanService` 新增 `snapshot(plan)`：安全解析 `itemsJson`，异常/空白返回空对象。
- [ ] 1.3 `PlanService` 新增 `reasonsFor(item, snapshot, exercise, recent)`：
  - 优先级：`matchedExercises.reason` → 情绪匹配 → grounding/通用兜底；
  - 禁用词过滤；截断 80 字；去重；最多 2 条；恒非空。
- [ ] 1.4 `view()` 输出 `reasons`；`view()` 内只解析一次快照、只查一次轨迹。
- [ ] 1.5 `adaptation()` 复用 `RecentEmotion.avgValence`，删除重复轨迹查询。
- [ ] 1.6 `PlanController.item()` 映射 `reasons`，空值归一为 `List.of()`。
- [ ] 1.7 `PlanDtos.PlanItemView` 增加 `List<String> reasons`。
- [ ] 1.8 `PlanServiceTest` 增加 9 组测试（见 spec §5.1），全部通过。

## 2. M-07 前端与小程序

- [ ] 2.1 `soul-frontend/src/types/domain.ts`：`PlanItemView.reasons: string[]`。
- [ ] 2.2 `PracticeView.vue`：guidance 下展示「为什么是它」；空数组不渲染。
- [ ] 2.3 `TodayView.vue`：展示第一条理由。
- [ ] 2.4 `miniprogram/src/api/app.ts`：`PlanItem.reasons?: string[]`。
- [ ] 2.5 `miniprogram/src/pages/today/index.vue`：展示第一条理由。
- [ ] 2.6 前端/小程序 typecheck 通过。

## 3. M-03 单报告打印

- [ ] 3.1 `ReportDetailView.vue` 新增「打印 / 存为 PDF」按钮（顶部 + 底部），`window.print()`。
- [ ] 3.2 新增 print-only 标题块与免责声明页脚。
- [ ] 3.3 打印时隐藏导航、批注/反馈卡、按钮、原始 JSON 折叠区。
- [ ] 3.4 打印样式：卡片无阴影、`break-inside: avoid`、标题后不分页。
- [ ] 3.5 浏览器走查：按钮存在、打印预览含标题/正文/免责声明。

## 4. M-03 档案目录与分页

- [ ] 4.1 `ArchiveView.vue` 快照视图新增目录（报告 / 周画像 / 情绪记录）。
- [ ] 4.2 三个章节加 `id` 锚点；屏幕可跳转。
- [ ] 4.3 标题页补充报告数、情绪点数、生成时间。
- [ ] 4.4 打印样式：周画像、情绪记录 `break-before: page`；页脚生成时间 + 免责声明。
- [ ] 4.5 浏览器走查：目录锚点、打印预览章节分页。

## 5. 应用壳打印修复

- [ ] 5.1 `App.vue` 增加 `@media print`：
  - `.stage` / `.canvas` / `main.screen` 高度自动、溢出可见；
  - `body` 白底、可滚动；
  - `@page` A4 边距。
- [ ] 5.2 给 `SvTabBar`、`SvToastHost`、`SvDialogHost`、`SvSheet`、`SvCrisisBanner` 加 `class="print-hide"`，打印隐藏。
- [ ] 5.3 确认打印媒体下不出现固定高度截断；屏幕布局不变。

## 6. 契约同步

- [ ] 6.1 后端：`mvn test -Dtest=OpenApiContractTest -Dopenapi.update=true` 更新 `soul-backend/openapi/openapi.json`。
- [ ] 6.2 审阅 diff：只允许 `PlanItemView.reasons` 新增。
- [ ] 6.3 `frontend`：`npm run gen:api`。
- [ ] 6.4 `miniprogram`：`npm run gen:api`。
- [ ] 6.5 再审阅两端 `schema.d.ts` diff。

## 7. 全门禁

- [ ] 7.1 后端 `mvn -q test`：全绿；记录用例数/跳过数。
- [ ] 7.2 前端 `npm run test`、`npm run typecheck`、`npm run build`。
- [ ] 7.3 小程序 `npm run test`、`npm run typecheck`、`npm run lint`、`npm run build:mp-weixin`。
- [ ] 7.4 golden 15：`node deploy/tools/snapshot_batch0.mjs` + diff，字段路径一致。
- [ ] 7.5 C guard：`node deploy/tools/check_c_guard.mjs` PASS。
- [ ] 7.6 ArchUnit / 分层冻结通过。

## 8. 文档与提交

- [ ] 8.1 更新 `.trae/specs/batch7-medium-assessment/assessment.md`：M-07 / M-03 标记「已实施」。
- [ ] 8.2 更新基线 `.trae/specs/establish-soul-voyage-baseline/tasks.md` 11.8 状态。
- [ ] 8.3 填写 `checklist.md`，记录证据与受限项。
- [ ] 8.4 提交 Batch 7A（仅包含本批文件）。
