# H-02 Checklist

- [x] A1 `GET /emotions/insights` 登录态可读，查询强制以 JWT userId 为键
- [x] A2 `weeks` 钳制 1..12，越界不报错
- [x] A3 无记录时返回空 weekly 与 `INSUFFICIENT`，不抛异常
- [x] A4 波动三档可复现：STEADY / WAVY / VOLATILE；样本不足为 INSUFFICIENT
- [x] A5 周摘要包含记录量、效价环比、主要压力源、误区线索
- [x] A6 压力源释义与微行动来自内容源；误区解释来自 `ContentStore`，缺失时中性兜底
- [x] A7 下一步按优先级至多 3 条；危机态只返回 `CARE_RESOURCES`
- [x] A8 全部生成文案不含诊断词（抑郁/焦虑症/PTSD/诊断等）
- [x] B1 `kg:stressor_definitions` 对既有库也能补种（不因 kg_node 已存在而跳过）
- [x] B2 OpenAPI 快照更新且 diff 仅包含新端点与 DTO（194 行纯新增）
- [x] B3 前端/小程序生成类型同步，无其他契约漂移
- [x] C1 洞察页展示本周小结与波动提示
- [x] C2 洞察页展示压力源/误区解释与下一步入口
- [x] C3 周画像列表附摘要标题；接口失败时既有图表仍可用
- [x] D1 后端 443 例 0 失败；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
- [x] D2 golden 15 场景字段路径一致
- [x] D3 C guard PASS（C 类零改动）
