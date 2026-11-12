# H-01 Checklist

- [x] A1 `GET /tasks/{taskNo}/steps` 仅属主可读，他人 403
- [x] A2 步骤时间线按 stepSeq/id 升序，字段足够解释失败原因
- [x] A3 `POST /tasks/{taskNo}/retry` 仅 FAILED 可重试，其余 400/2001；属主断言
- [x] A4 重试复用断点续跑语义（不新建任务、不重复已成功步骤）
- [x] A5 重试动作写审计（TASK_RETRY）
- [x] B1 OpenAPI 快照更新且 diff 仅包含新增两个端点与 DTO（105 行纯新增）
- [x] B2 前端/小程序生成类型同步，无其他契约漂移
- [x] C1 Web 任务中心可列出历史与失败原因
- [x] C2 抽屉时间线展示步骤/状态/耗时/错误
- [x] C3 FAILED 提供重试入口；PENDING/RUNNING 可实时跟随并在终态停止
- [x] C4 任务状态文案覆盖 CANCELLED/EXPIRED
- [x] D1 后端 438 例全绿；前端 112 例、typecheck 与 build 绿
- [x] D2 golden 15 场景字段路径一致
- [x] D3 C guard PASS（210 个变更路径，C 目录零改动）
