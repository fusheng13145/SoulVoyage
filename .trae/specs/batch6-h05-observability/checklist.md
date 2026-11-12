# H-05 Checklist

- [x] A1 `AdminMetricsController` 不再依赖 Repository，overview 行为不变
- [x] A2 失败告警：0 失败 OK；失败率/失败数阈值可复现；最近失败字段完整
- [x] A3 成本预算：token/成本/占用率正确；80%/100% 分级正确
- [x] A4 质量抽检只读 feedback/标题，不解密正文；差评率与样本不足正确
- [x] A5 慢请求仅记 ≥阈值、排除 actuator/护栏自身/query；TopN 倒序
- [x] A6 慢请求缓冲上限 500，重启清零，不记录身份/请求体
- [x] B1 `GET /admin/metrics/guardrails` 仅 ADMIN + admin:task
- [x] B2 OpenAPI 纯新增 guardrails 与 DTO（250 行）
- [x] B3 前端/小程序生成类型同步
- [x] C1 总览页失败告警卡可展示正常/告警/空态
- [x] C2 总览页预算卡可展示占用率与超限状态
- [x] C3 总览页质量抽检卡可展示差评条目并高亮
- [x] C4 总览页慢请求 TopN 可展示 method/path/status/耗时
- [x] D1 后端 470 例 0 失败；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
- [x] D2 golden 15 场景字段路径一致
- [x] D3 C guard PASS（C 类零改动）
