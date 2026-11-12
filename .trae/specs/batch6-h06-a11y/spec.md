# H-06 无障碍与键盘变更单

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 H-06；基线 tasks.md Task 11.6。
> 性质：纯前端可达性改造。**不改业务逻辑、不改接口契约、不动 C 类文件**。
> 范围：全局壳（skip link / 焦点管理 / 快捷键）+ 浮层焦点陷阱 + 对比度与命中区收敛。

## 1. 目标

让只用键盘或读屏的用户也能完整走完主流程：

| 能力 | 交付 |
|---|---|
| 跳过重复导航 | skip link 直达主内容区 |
| 焦点可预期 | 路由切换后焦点落到主区；浮层打开自动聚焦、关闭还原 |
| 键盘可导航 | Tab 顺序合理；对话框/抽屉内焦点不逃逸；`Alt+1..5` 直达主导航 |
| 读屏可理解 | 图标按钮有可读名称；装饰图形对辅助技术隐藏；状态变化有播报 |
| 对比度与命中区 | 小字辅助色提升到 AA；图标按钮 ≥44px |

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `App.vue` | 无 skip link；路由切换不移动焦点，键盘用户需重新 Tab 一整页 |
| `SvDialogHost` | 有 `Escape` 关闭，但无初始聚焦、无焦点陷阱、关闭后焦点丢失 |
| `SvSheet` | 有 `Escape` 关闭与 `role="dialog"`，同样缺焦点陷阱与还原 |
| `SvTabBar` | 有 `aria-label`/`aria-current`，无快捷键声明 |
| `base.css` | 已有 `:focus-visible` 与 `prefers-reduced-motion` 降级 |
| `tokens.css` | `--sv-label3` 小字对比度约为 AA 临界值 |
| `SvSkeleton` | 纯视觉占位，未对读屏隐藏 |

## 3. 改动

### 3.1 全局壳

`App.vue`：

- 顶部新增 `<a class="skip-link" href="#sv-main">跳到主要内容</a>`，聚焦时才显形。
- `<main id="sv-main" tabindex="-1">`；`router.afterEach` 后把焦点移到主区（`preventScroll`），使 Tab 从新页面开头继续。
- 监听 `Alt+1..5`：跳转 `/today`、`/diaries`、`/practice`、`/insights`、`/me`；在输入框内不拦截。
- 路由切换时为主区播报：`aria-live="polite"` 的隐藏区域输出页面标题。

### 3.2 浮层焦点管理

新增 `composables/focusTrap.ts`：

- `focusableIn(root)`：收集可聚焦元素（排除 `disabled`、`tabindex=-1`、`hidden`）。
- `nextFocusIndex(current, length, shift)`：Tab/Shift+Tab 环形索引（纯函数，可单测）。
- `useFocusTrap(target, active)`：激活时聚焦第一个可聚焦项并监听 `keydown`；失活时把焦点还原到打开前的元素。

`SvDialogHost` 与 `SvSheet` 接入：打开时聚焦主按钮/面板，Tab 循环在浮层内，`Escape` 关闭后焦点回到触发元素。

### 3.3 读屏与命中区

- `SvSkeleton`：`aria-hidden="true"`。
- `SvTabBar`：每个 tab 增加 `aria-keyshortcuts="Alt+1"` 等声明。
- `SvNavBar` 返回按钮、`DiaryDetailView`/`ArchiveView` 图标按钮：确认 `aria-label` 完整（前者已具备）。
- `base.css`：`.skip-link` 样式（聚焦时可见，平时移出视口）；图标按钮最小命中区 44×44。
- `tokens.css`：`--sv-label3` 调整为 AA 达标值；补 `--sv-hit: 44px`。

### 3.4 快捷键说明

在 `MeView` 增加一小段「键盘操作」说明（纯静态文本）：skip link、`Alt+1..5`、`Esc` 关闭浮层、Tab 循环。

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| Skip link | Tab 首帧出现；回车后焦点进入主内容区 |
| 路由焦点 | 切换页面后焦点在主区，Tab 从新页首项继续 |
| 浮层 | 打开即聚焦，Tab 不逃逸，Esc 关闭后焦点回到触发按钮 |
| 快捷键 | `Alt+1..5` 在非输入态切换主导航；输入框内不拦截 |
| 读屏 | 骨架屏与图标对辅助技术隐藏；导航有 `aria-current`；toast 有 `aria-live` |
| 对比度 | `--sv-label3` 小字在明暗主题下均达 AA |
| 回归 | 前端 112+ 例、typecheck、build 全绿；后端/小程序无改动；golden 与 C guard 不受影响 |
| 回滚 | 纯前端样式与组合式函数；回滚删除即可 |

## 5. 批准状态

- [x] 用户「提交 H-05 并继续 H-06」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 全局壳 | `App.vue` 新增 skip link、`main#sv-main`、路由后焦点归位与 polite 标题播报；`Alt+1..5` 主导航、`?` 帮助、`Esc` 关闭 |
| 浮层 | 新增 `focusTrap.ts`；`SvDialogHost`/`SvSheet` 打开聚焦、Tab 循环、关闭还原焦点；`overlayDepth` 同步 |
| 读屏/命中区 | `SvSkeleton` aria-hidden；`SvTabBar` aria-keyshortcuts + 左右键；MeView 增加键盘说明 |
| 对比度 | `--sv-label3` 明暗均达 AA：light 5.78/6.30、dark 6.15/5.62；新增 `--sv-hit:44px` 与 `.sv-hit` |
| 测试 | 前端 116 例（新增 4 例 focusTrap）+ typecheck + build；后端 470 例回归；小程序 20 例 + typecheck/lint |
| 走查 | 浏览器键盘走查：skip link、`?` 打开帮助面板、焦点锁在面板内、Esc 关闭均 PASS |
| 回归 | 纯前端改动，未改接口/后端/小程序；C guard PASS（11 个变更路径，C 类零改动） |
