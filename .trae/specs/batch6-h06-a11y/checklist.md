# H-06 Checklist

- [x] A1 skip link 为 Tab 首站，回车后焦点进入 `#sv-main`
- [x] A2 路由切换后焦点在主区，Tab 从新页首项继续
- [x] A3 页面切换有 polite 播报（标题）
- [x] B1 对话框打开即聚焦确认按钮，Tab/Shift+Tab 在框内循环
- [x] B2 对话框 Esc/确认/取消关闭后焦点回到触发元素
- [x] B3 抽屉打开即聚焦面板内首项，Esc 关闭后焦点还原
- [x] C1 `Alt+1..5` 切换主导航；输入框/文本域内不拦截
- [x] C2 `SvTabBar` 声明 `aria-keyshortcuts`，`aria-current` 正确
- [x] C3 `SvSkeleton` 对辅助技术隐藏
- [x] C4 `--sv-label3` 明暗主题对比度均 ≥4.5:1（light 5.78/6.30、dark 6.15/5.62）
- [x] C5 图标按钮命中区 ≥44px；`.skip-link` 聚焦可见
- [x] D1 `focusTrap.spec.ts` 覆盖环形索引与可聚焦元素收集
- [x] D2 前端 116 例 + typecheck + build 全绿
- [x] D3 后端 470 例回归；小程序 20 例 + typecheck/lint；C guard PASS
