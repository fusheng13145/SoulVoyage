# Batch 7A · 验收清单

> 状态：**代码与自动化门禁通过；打印视觉复核待人工执行**（浏览器自动化环境不可用）。
> 红线核对：无数据库迁移、无 `plan_item.reason`、无读取时 LLM 调用、无 PDF 依赖、C 类零改动。

## A. M-07 推荐解释性

- [x] A1 `GET /plans/active` 每项 `reasons` 为 1–2 条非空短句。  
  证据：本地后端实跑用户 `m07check155522`，计划项返回 `["（mock）匹配当前「平静」，认知书写三栏表约 10 分钟即可"]`。
- [x] A2 `GET /plans`、`GET /plans/{id}` 同样返回 `reasons`。  
  证据：`PlanController.item()` 统一映射；`PlanServiceTest` 覆盖 `list()`。
- [x] A3 `items_json.matchedExercises` 命中时理由与报告中的推荐理由一致（截断 80 字）。  
  证据：`reasonsPreferMatchedSnapshotReason`、`reasonsStableTruncatedAndAtMostTwo`。
- [x] A4 匹配理由含诊断/疗效禁用词时被丢弃。  
  证据：`reasonsRejectBannedSnapshotReason`（含「治疗方案/治愈」）。
- [x] A5 无匹配理由时按近 7 天主导情绪 + `applyEmotions` 生成理由。  
  证据：`reasonsRejectBannedSnapshotReason`、`reasonsTieBreakToLatestEmotion`。
- [x] A6 主导情绪并列时取最近一条记录。  
  证据：`reasonsTieBreakToLatestEmotion`（焦虑/悲伤各 2 次，取最近焦虑）。
- [x] A7 危机 grounding 返回 grounding 兜底句。  
  证据：`reasonsGroundingFallback`。
- [x] A8 无报告、无轨迹、普通练习返回通用兜底句。  
  证据：`reasonsGenericFallback`。
- [x] A9 同一输入多次读取一致；每项 ≤2 条、无重复。  
  证据：`reasonsStableTruncatedAndAtMostTwo`（两次读取相等；第一条 80 字；共 2 条）。
- [x] A10 `view()` 每次只解析一次 `itemsJson`、只查一次轨迹；`list()` 多计划只查一次。  
  证据：`listQueriesRecentEmotionOnce`、`adaptationStillWorksWithSharedRecentEmotion`。
- [x] A11 读取路径未解密 `report`、未调用 LLM、未新增数据库列。  
  证据：代码仅依赖 `itemsJson` + `emotion_trajectory`；无 Flyway 变更；`crypto` 未用于理由。
- [x] A12 PracticeView 显示「为什么是它」；TodayView 显示第一条；空理由不渲染。  
  证据：模板已改；前端 `typecheck`/`build` 通过；构建产物含「为什么是它」。
- [x] A13 小程序今日页显示第一条理由，类型与接口一致。  
  证据：`PlanItem.reasons?: string[]`；小程序 `typecheck`/`lint`/`build:mp-weixin` 通过。

## B. M-03 报告导出 2.0

- [x] B1 报告详情页有「打印 / 存为 PDF」，调用浏览器打印。  
  证据：`ReportDetailView.vue` 顶部/底部按钮 + `doPrint()`；构建产物含「打印 / 存为 PDF」。
- [x] B2 打印内容含报告标题、类型、时间、风险档、正文、免责声明。  
  证据：print-only 标题块 + 现有正文卡 + 页脚免责声明（视觉待复核，见 F1）。
- [x] B3 打印不含导航、收藏/反馈控件、按钮、原始 JSON 折叠区。  
  证据：导航 `print-hide`；批注卡/按钮 `no-print`；原始 JSON 所在兜底卡仅未识别类型展示，打印未单独保留。
- [x] B4 档案快照有目录（报告 / 周画像 / 情绪记录）与可跳转锚点。  
  证据：`nav.toc` + `#snap-reports` / `#snap-profiles` / `#snap-emotions`。
- [x] B5 打印从标题页开始，周画像、情绪记录各自新页。  
  证据：`title-page` / `toc` `break-after: page`；`#snap-profiles, #snap-emotions` `break-before: page`；构建产物含规则。
- [x] B6 打印无应用壳固定高度导致的截断。  
  证据：`App.vue` `@media print` 解除 `.canvas`/`main.screen` 高度与 `overflow`；构建产物含 `@media print`。
- [x] B7 页脚仅含生成时间与免责声明，不含正文敏感内容。  
  证据：`print-foot` 固定文案。
- [x] B8 导出仍走现有一次性快照链路；未新增导出接口。  
  证据：`ArchiveController` 零改动；OpenAPI 无新增路径。
- [x] B9 未新增 PDF 依赖。  
  证据：`frontend/package.json` 未新增依赖。

## C. 契约

- [x] C1 `backend/openapi/openapi.json` diff 仅 `PlanItemView.reasons` 新增。  
  证据：`+6` 行，仅 `reasons: array<string>`。
- [x] C2 Web `schema.d.ts` 与 OpenAPI 一致。  
  证据：`+1` 行 `reasons?: string[]`。
- [x] C3 小程序 `schema.d.ts` 与 OpenAPI 一致。  
  证据：`+1` 行 `reasons?: string[]`。
- [x] C4 旧客户端忽略新字段；新客户端对缺失 `reasons` 降级。  
  证据：模板使用 `it.reasons?.length`；小程序 `it.reasons && it.reasons.length`。

## D. 门禁

- [x] D1 后端 `mvn -q test` 全绿：**478 tests / 0 failures / 0 errors / 9 skipped**（66 个 surefire 文件）。
- [x] D2 前端 `npm run test` 全绿：**116 tests / 12 files**。
- [x] D3 前端 `npm run typecheck`、`npm run build` 通过。
- [x] D4 小程序 `npm run test` 全绿：**20 tests / 4 files**。
- [x] D5 小程序 `npm run typecheck`、`npm run lint`、`npm run build:mp-weixin` 通过。
- [x] D6 golden 15 场景执行完成；仅 `10-plan.json` 新增 `reasons`（+9 行）；14/15 动态噪声已还原。  
  说明：`14-admin-task/retry` 因无 FAILED 任务 SKIPPED（与基线一致）。
- [x] D7 C guard PASS：扫描 16 个变更路径，清单 15 条，未命中 C 类。
- [x] D8 ArchUnit / 分层冻结通过（`mvn test` 内）。

## E. 文档与提交

- [x] E1 评估文档标记 M-07 / M-03 已实施。
- [x] E2 基线 tasks.md 11.8 状态更新。
- [ ] E3 提交仅包含 Batch 7A 文件（待用户确认后提交；提交信息说明读取时派生、无迁移、浏览器打印）。

## F. 视觉复核（待人工）

- [ ] F1 浏览器实际打印预览：单报告标题/正文/免责声明完整，无控件残留。
- [ ] F2 档案打印预览：标题页、目录、报告、周画像、情绪记录分页正确，长档案无截断。
- [ ] F3 屏幕端目录锚点滚动位置正确（`scroll-margin-top` 生效）。

> 受限原因：TRAE 浏览器自动化返回 `No current window`，无法创建浏览器窗口；代码与构建产物静态检查已通过。
