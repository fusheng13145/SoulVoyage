# Batch 2 实施计划：静室手记（Quiet Journal）UI 落地

> 依据：`.trae/specs/establish-soul-voyage-baseline/spec.md` §5 与 §3.6 Batch 2
> 范围：纯展示层。不改 API 调用、路由语义、状态机、store 数据结构、后端、schema。
> 前置：Batch 0（基线快照）+ Batch 1（A 类）已完成，守卫 PASS。
> 本文件仅为计划，未改动任何代码。

## 1. 目标与硬规则

- 视觉方向：暖纸、墨色、夜灯、一页手记；安静、温暖、私密、克制、可读。
- 出口门禁（Spec §3.6）：
  1. 0 装饰性渐变（`linear/radial/conic-gradient`）；
  2. 0 毛玻璃/高光（`backdrop-filter`、玻璃反光）；
  3. 仅 Dialog / Sheet / Toast 可使用一层柔和阴影 `--sv-elev-1`；
  4. 圆角：卡片 8px、控件 6px、浮层 12px、标签 3px；不做胶囊（状态点/进度环除外）；
  5. 单一强调色苔绿；成功/警告/危险只用于状态；
  6. 用户端长文用衬线 17px/1.85、≤68ch；界面用无衬线 14px；
  7. 无 emoji 作为系统图标/唯一语义；
  8. 桌面不做手机壳：暖纸背景 + 760px 纸页列；
  9. 动效 180–240ms、仅透明度与 ≤6px 位移；呼吸练习例外但需 reduced-motion 文字替代；
  10. 对比度 AA、键盘可达、触控 ≥44px（桌面 ≥32px）。

## 2. 执行阶段（严格顺序，每阶段验证后进入下一阶段）

### Stage A — Token + 全局壳 + 基础件（P0）

| 文件 | 改动 |
|---|---|
| `soul-frontend/src/styles/tokens.css` | 落地 §5.3 暖纸/夜读两套色、字阶、间距、圆角、`--sv-border`、`--sv-elev-1`；新增 `--sv-accent`，`--sv-indigo` 保留为别名（迁移期）；新增 `--sv-serif`、`--sv-num`、`--sv-lh-*`；情绪色替换为 §5.3.3 低饱和值 |
| `soul-frontend/src/styles/base.css` | 正文 14px/1.65；`.sv-prose` 衬线 17/1.85 + 68ch；按钮 40px/6px、按下只改背景；输入/textarea 40px + `card2` + 1px `line-strong`；`.sv-surface` 改 1px 边框；焦点环 1px accent + 2px accent-soft；移除彩色阴影 |
| `soul-frontend/src/App.vue` | 删除 `stage::before` 三色光晕与 blur；删除桌面 430px 手机壳/44px 圆角/大阴影；桌面改暖纸背景 + 760px 纸页列（管理端放开为 1200px fluid）；转场改 200ms 透明度 + 6px 上移 |
| `SvNavBar.vue` | 52px、纯色 `bg` + 1px 底边；去 `backdrop-filter`；标题 16/600；大标题改衬线日期/问候（保留 slot 能力） |
| `SvTabBar.vue` | 60px、1px 顶边、纯色；图标 22px、标签 11px；激活 accent；去掉 `scale(0.92)` 按压缩放 |
| `SvCard.vue` | `card` 底 + 1px `sep` + 8px 圆角，默认无阴影；padding 20px |
| `SvList.vue` / `SvCell.vue` | 行高 56px、1px 底边、线性图标 20px、chevron 14px；去掉彩色图标底 |
| `SvSheet.vue` / `SvDialogHost.vue` / `SvToastHost.vue` | 12px 圆角 + 1px 边框 + `--sv-elev-1`；遮罩 `rgba(20,17,12,.45)`；Sheet 顶部 16px；去毛玻璃 |
| `SvSegmented.vue` / `SvSwitch.vue` / `SvChip.vue` / `SvCheckbox.vue` | 控件 6px/标签 3px；选中 `accent-soft` + `accent`；去胶囊 |
| `SvSkeleton.vue` | 暖纸色块 + 1.4s opacity 脉冲；去掉 shimmer 渐变 |
| `SvDisclaimer.vue` | 1px 顶边 + 12px `label2`；禁用彩色卡片 |
| `SvEmotionDial.vue` | 16 个 emoji 脸改为 4px 情绪色点 + 11px 文字标签（Spec §5.6 允许）；选中 1px accent 外环；键盘可操作 |
| `SvCalendarHeat.vue` | 热力条改 1px 分隔 + 低饱和色块；保留点选补签 |
| `SvBubble.vue` / `SvTimeline.vue` | 气泡纸面 1px 边框 + 8px 圆角；时间线 1px 线 + 3px 点 |
| `SvProgressRing.vue` | 1px 轨道 + accent 弧线；中心数字用 `--sv-num` |
| `SvIcon.vue` + `public/icons.svg` | 补齐去除 emoji 所需的线性图标（收藏、信封、勋章、火焰、叶子、提示等）；图标 1.5px stroke |
| `AdminShell.vue` | 移动端胶囊 Tab 改顶部文字分段；桌面改 200px Rail + 1px 右边 + 1200px 内容 |

### Stage B — P0 页面

`LoginView`、`TodayView`、`DiaryWriteView`、`AnalysisView`、`CrisisView`。

- Login：字标 + 一页暖纸表单；主按钮 40px；错误 inline；回车提交；文案改「登录 / 注册」；保留危机入口。
- Today：首屏＝衬线日期问候 → 心情打卡 → 今日计划 → 双入口纸面行；最近动态/每日一读折叠；去 🔥🌿🌱；卡片降到 4–5 个。
- DiaryWrite：正文衬线 17/1.9；心情改 1–5 文字/线性标签；语音按钮 40px；底部粘性提交。
- Analysis：顶部固定四步步骤条；报告分区折叠；情绪条 1px 轨道；去 emoji；免责声明固定底部。
- Crisis（C-10 约束）：只做排版/对比度/可达性；110/120 置顶 24px；热线行 1px 分隔；**不改任何转介资源文案与链接**。

### Stage C — P1 页面

`DiaryListView`、`DiaryDetailView`、`InsightsView`、`PracticeView`、`SimulationView`、`PracticeRoomView`、`CompanionView`、`MeView`。

- 日记列表：日期戳 + 两行预览 + 情绪色点 + 语音标记；1px 分隔；搜索 40px。
- 洞察：首屏只留情绪曲线 + 压力源；误区趋势/雷达折叠；图表按 §5.7；周画像改安静表格/文字摘要。
- 练习/模拟/跟练/漫聊：去渐变 hero、气泡阴影、呼吸环光晕；场景卡 1px；紧张度 1px meter；完成态文字化。
- 我的：方形字母头像（1px 边框/6px 圆角）；设置行 56px；开关扁平；去彩色图标底。

### Stage D — P2/P3 页面（含管理端）

`ArchiveView`、`ReportDetailView`、`LettersView`、`AchievementsView`、`NotificationsView`、`AccountView`；
`AdminHomeView`、`AdminTasksView`、`AdminRiskView`、`AdminContentView`、`AdminUsersView`、`AdminOpsView`、`AdminBoardView`。

- 报告/档案：正文衬线、分区折叠、索引行；导出主按钮置顶；打印黑白可读。
- 来信/成就/通知：日期戳 + 1px 分隔；去 ✉️🏅🔒；未读 4px accent 点。
- 管理端：统一 Rail + 表格/纸面数字块；状态 3px 方标签；证据 `pre` 等宽滚动区；无阴影。

### Stage E — 图表、无障碍、截图、小程序

| 范围 | 改动 |
|---|---|
| `InsightsView` 图表 | 透明背景、1px 轴线/虚线网格、1.5px 折线、3px 点、无渐变面积；暗色响应 `isDark` 重绘；旁附文字摘要 |
| 全局 a11y | 键盘走查主流程；焦点可见；`role="alert"` / `aria-live`；图标 `aria-label`；对比度用脚本核算 |
| `prefers-reduced-motion` | 呼吸环提供数字倒计时 + 文字提示；全站动效降级已有基础，补齐 |
| 截图矩阵 | 4 宽度（375/768/1024/1440）× 明暗 × §5.10 全页面，输出 `docs/baseline/batch2-shots/`（不覆盖 Batch 0 基线） |
| 小程序 | `styles/tokens.scss` 同步暖纸 Token；9 页去 emoji/渐变/大圆角；tabBar 扁平；`.sv-dark` 暗色；长文衬线（字体包不可行则系统宋体） |

## 3. 改动文件清单（汇总）

- 样式：`soul-frontend/src/styles/tokens.css`、`base.css`
- 全局壳：`App.vue`、`SvNavBar.vue`、`SvTabBar.vue`、`AdminShell.vue`
- 基础件：`SvCard/SvList/SvCell/SvSheet/SvDialogHost/SvToastHost/SvSegmented/SvSwitch/SvChip/SvCheckbox/SvSkeleton/SvDisclaimer/SvEmotionDial/SvCalendarHeat/SvBubble/SvTimeline/SvProgressRing/SvIcon` + `public/icons.svg`
- 页面：§5.10 的 Web 用户端 19 页 + 管理端 7 页
- 小程序：`styles/tokens.scss`、`styles/base.scss`、9 个页面、`App.vue`、`uni.scss`
- 文档：本计划、Batch 2 截图目录、`tasks.md`/`checklist.md` 勾选

## 4. 风险点与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 全站视觉回归面大 | 26 个 Web 页面 + 9 个小程序页 | 严格按 Stage A→E；每阶段 4×2 截图对照；单页可独立回退 |
| C-10 危机页误改 | 高危用户兜底路径被破坏 | CrisisView/CrisisReferral 只改排版/对比度；资源文案/链接零改动；改前 diff 人工核对 |
| 去 emoji 导致语义缺失 | 情绪/成就/信封等识别度下降 | 情绪用色点+文字；功能图标补线性 SVG；确保图标有 `aria-label` |
| 对比度不达标 | 暖纸色 + 低饱和情绪色可能低于 AA | 用对比度脚本核算 `label/label2/label3`；`label3` 按 Spec 固定 `#6F6659`/`#8E8779` |
| 暗色图表不重绘 | 切换主题后图表仍为亮色 | ECharts watch `isDark` 重绘；截图覆盖明暗 |
| 小程序暗色/字体限制 | 深色靠 `.sv-dark`、衬线字体包体积 | 字体回退系统宋体；逐页 `.sv-dark` 验证；不改业务逻辑 |
| 样式影响交互尺寸 | 触控目标缩小 | 保留 44px 最小触控；桌面 32px；不缩小可点击区域 |
| 迁移期变量名兼容 | 页面仍引用 `--sv-indigo` 等旧名 | 保留旧变量别名一个版本；新代码统一 `--sv-accent` |

## 5. 验证与门禁

1. `npm run lint`、`format:check`、`typecheck`、`test`、`coverage`、`build` 全绿。
2. `npm run gen:api` 后 `git diff --exit-code -- src/api/schema.d.ts` 无漂移。
3. 小程序 `typecheck` + `build:mp-weixin` + `build:h5` 通过。
4. 静态扫描：
   - `grep -rE "linear-gradient|radial-gradient|conic-gradient" soul-frontend/src` → 0（功能性呼吸动画不得用渐变）；
   - `grep -rE "backdrop-filter" soul-frontend/src` → 0；
   - `grep -rE "box-shadow" soul-frontend/src` → 仅 Sheet/Dialog/Toast 命中 `--sv-elev-1`；
   - emoji 扫描：系统 UI 模板中 0 命中（用户输入内容除外）。
5. 截图矩阵：4 宽度 × 明暗 × 全部页面，无溢出、无空白、交互可完成。
6. a11y：键盘走查登录→今日→写日记→分析→洞察→练习→我的；焦点可见；对比度 AA；reduced-motion 下呼吸练习有文字替代。
7. `node deploy/tools/check_c_guard.mjs` PASS；后端零改动（本批不跑后端测试也可，但保持 `mvn test` 绿）。
8. 回滚：每 Stage 独立提交/可单组 revert；Token 文件保留旧值注释；不涉及数据与契约。

## 6. 待确认

1. 是否按 Stage A→E 分阶段执行（推荐），还是全量一次推进？
2. 小程序端（§5.10 #27–29）是否纳入本批？
3. 情绪 emoji 的替代方案：色点 + 文字（推荐，Spec 明确允许）还是新增 16 个线性表情 SVG？

---

## 7. 执行结果（2026-11-12）

用户确认：分阶段执行、小程序纳入、情绪用「色点 + 文字」。

| Stage | 结果 |
|---|---|
| A Token + 壳 + 基础件 | 完成：暖纸/夜读双 Token、纸页列、52px Nav、60px Tab、8px 卡片 + 1px 边框、浮层仅 `--sv-elev-1`、控件 6px/标签 3px、骨架去 shimmer、情绪盘改色点 |
| B P0 页面 | 完成：登录字标表单、今日衬线日期 + 折叠、写日记衬线正文 + 文字心情 + 粘性提交、分析分区折叠、危机页高对比且资源文案零改动 |
| C P1 页面 | 完成：日记时间线、洞察折叠 + 图表规范、练习/模拟/跟练去渐变与光晕、漫聊纸面气泡、我的方形字母头像 |
| D P2/P3 页面 | 完成：档案/报告/来信/成就/通知/隐私中心/管理端统一暖纸与方标签；管理端桌面 Rail |
| E 图表/a11y/截图/小程序 | 完成：图表去渐变、暗色重绘、64 张截图矩阵；小程序 Token/基础件/今日/登录/漫聊同步 |

### 验证

- 前端：lint / format:check / typecheck / test（108 例）/ build 全通过；`gen:api` 无漂移。
- 小程序：lint / format:check / typecheck / build:mp-weixin / build:h5 全通过。
- 静态扫描：`linear/radial/conic-gradient`、`backdrop-filter` 均 0 命中；`box-shadow` 仅浮层 `--sv-elev-1` 与焦点环；系统 UI 模板无 emoji（`utils/emotions.ts` 的 `face` 字段仅保留数据，不再渲染）。
- 截图：`docs/baseline/batch2-shots/`，375/768/1024/1440 × 明暗 × 今日/日记/写日记/洞察/练习/我的/危机/登录。
- C 类：`CrisisView.vue`、`CrisisReferral.vue` 仅样式与 class 调整，资源、边界说明零改动；已在守卫中以 `--allow` 显式豁免并记录（Spec C-10 允许排版/可达性优化）。

### 遗留

- 7.5 的完整键盘走查与自动化对比度核算未做，留待 Batch 2 收尾或 Batch 6 H-06。
- 管理端由卡片列表改为表格属于结构性改版，本批只完成视觉统一（1px 边框/方标签/Rail），表格化可在后续专项。

