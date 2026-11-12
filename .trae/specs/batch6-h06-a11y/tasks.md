# H-06 Tasks

- [x] Task 1: 焦点工具与全局壳
  - [x] 1.1 `composables/focusTrap.ts`：`focusableIn` / `nextFocusIndex` / `useFocusTrap`
  - [x] 1.2 `App.vue`：skip link、`main#sv-main`、路由后焦点归位、页面标题播报
  - [x] 1.3 `App.vue`：`Alt+1..5` 主导航快捷键（输入框内不拦截）

- [x] Task 2: 浮层接入
  - [x] 2.1 `SvDialogHost` 接入焦点陷阱与还原
  - [x] 2.2 `SvSheet` 接入焦点陷阱与还原

- [x] Task 3: 读屏与样式
  - [x] 3.1 `SvSkeleton` `aria-hidden`；`SvTabBar` `aria-keyshortcuts`
  - [x] 3.2 `base.css`：`.skip-link`、图标按钮 44px 命中区
  - [x] 3.3 `tokens.css`：`--sv-label3` AA 调整、`--sv-hit`
  - [x] 3.4 `MeView` 键盘操作说明

- [x] Task 4: 测试
  - [x] 4.1 `focusTrap.spec.ts`：环形索引/可聚焦元素收集
  - [x] 4.2 前端 116 例 + typecheck + build

- [x] Task 5: 收口
  - [x] 5.1 更新基线 tasks.md 11.6（含 7.5 键盘走查）与本变更单落地记录
  - [x] 5.2 C guard 复核（无 C 类改动）

# Task Dependencies

- Task 2 依赖 Task 1.1。
- Task 3/4 依赖 Task 1–2。
- Task 5 依赖全部。
