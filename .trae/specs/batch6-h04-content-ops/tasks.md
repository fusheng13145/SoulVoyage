# H-04 Tasks

- [x] Task 1: 数据模型与迁移
  - [x] 1.1 `V3__content_ops.sql` 新增 `content_draft` / `content_revision`
  - [x] 1.2 `ContentDraftEntity/Repository`、`ContentRevisionEntity/Repository`
  - [x] 1.3 `ContentType` 枚举与路径解析

- [x] Task 2: 校验器
  - [x] 2.1 `ContentSchemaValidator`：KG/SCENE/EXERCISE 类型化校验 + 状态归一化
  - [x] 2.2 长度/闭集/前缀/交叉引用（压力源闭集）规则
  - [x] 2.3 `ContentSchemaValidatorTest` 覆盖合法/非法样例

- [x] Task 3: 内容运营服务
  - [x] 3.1 `ContentOpsService`：live 快照、草稿 CRUD、预览
  - [x] 3.2 发布：校验 + 冲突检查 + 写 live + revision + bump + 删草稿 + 审计
  - [x] 3.3 回滚：历史快照校验 + 写 live + 新 ROLLBACK revision + bump + 审计
  - [x] 3.4 既有三类 PUT 接入同一发布管线
  - [x] 3.5 `ContentOpsServiceTest`：草稿/发布/冲突/回滚/校验阻断

- [x] Task 4: 管理端接口
  - [x] 4.1 `ContentAdminController` 新增 draft/snapshot/revisions/rollback 端点
  - [x] 4.2 `ContentAdminDtos`/`ContentOpsDtos` 契约
  - [x] 4.3 更新 `ContentAdminControllerTest`

- [x] Task 5: 契约更新
  - [x] 5.1 OpenAPI 快照更新并审阅（纯新增 563 行）
  - [x] 5.2 前端/小程序 `npm run gen:api`

- [x] Task 6: 管理端 UI
  - [x] 6.1 `api/admin.ts` 草稿/预览/发布/历史/回滚接口
  - [x] 6.2 `AdminContentView.vue` 编辑器、校验反馈、发布、历史与回滚

- [x] Task 7: 门禁与收口
  - [x] 7.1 后端 461 例；前端 112 例 + typecheck/build；小程序 20 例 + typecheck/lint
  - [x] 7.2 golden 15 场景字段路径一致；C guard PASS
  - [x] 7.3 更新基线 tasks.md 11.4 与本变更单落地记录

# Task Dependencies

- Task 3 依赖 Task 1–2。
- Task 4 依赖 Task 3。
- Task 5 依赖 Task 4。
- Task 6 依赖 Task 5。
- Task 7 依赖 Task 1–6。
