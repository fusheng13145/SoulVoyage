# H-04 内容运营闭环变更单

> 上位基准：`.trae/specs/establish-soulvoyage-baseline/spec.md` §4.2 H-04；基线 tasks.md Task 11.4。
> 性质：扩展功能（跨模块）。触及内容真源，必须可预览、可回滚、可审计；**C-04 诊断词表/闭集 Schema 不动**。
> 范围：后端内容草稿/发布/回滚链路 + 管理端内容页；小程序/用户端不新增入口。

## 1. 目标

把现有「直接 upsert 即生效」升级为可运营闭环：

```text
编辑草稿 → 校验预览 → 发布（生成版本快照）→ 回滚到任意历史版本
```

- 草稿未发布不影响线上内容。
- 发布前必须通过类型化 schema 校验（字段、长度、闭集、交叉引用）。
- 每次发布/回滚生成不可变快照，支持一键回滚。
- 发布/回滚/草稿操作全部落审计；线上快照通过 `content:version` 失效并触发 Neo4j 懒同步。

## 2. 现状（已核实）

| 位置 | 现状 |
|---|---|
| `ContentAdminController` | 三类内容 PUT 直接写 live 表并 bump，无草稿、无校验、无历史、无回滚 |
| `kg_node` / `scene_card` / `exercise_library` | 只有 live 行与 status；无版本快照 |
| `content_meta` | 只有 `content:version` 与字典；不适合存大体积历史 |
| `AdminContentView.vue` | 只能上下架 + 展开 JSON；无编辑/预览/发布/回滚 |
| Flyway | 已有 V1/V2；C-06 允许新增迁移文件，不改历史语义 |

## 3. 改动

### 3.1 数据模型（新增 V3 迁移，不改既有表）

`backend/src/main/resources/db/migration/V3__content_ops.sql`：

| 表 | 用途 | 关键列 |
|---|---|---|
| `content_draft` | 每个内容一条草稿 | `content_type, code, payload_json, base_revision_id, note, updated_by, updated_at`；UK(type, code) |
| `content_revision` | 不可变发布/回滚快照 | `content_type, code, revision_no, payload_json, action(PUBLISH/ROLLBACK), source_revision_id, published_by, published_at`；UK(type, code, revision_no) |

- 不修改 `deploy/sql/schema.sql`（C-06）：V3 是本次唯一增量事实源；后续若重建 schema.sql 需单独走 C-06 审批。
- `payload_json` 存**完整可重放快照**（与草稿/发布请求同构），不是 live 行差量。

### 3.2 内容类型与快照

`ContentType`：`KG` / `SCENE` / `EXERCISE`，路径兼容 `kg|scene|scenes|exercise|exercises`。

快照形状：

```text
KG:       { type, name, payload, status }
SCENE:    { title, description, difficulties[], npcName, relation, persona,
            goalDimensions[], maxTurns, openingLines, tags[], recommendedFor[], status }
EXERCISE: { name, applyEmotions[], steps[], durationMin, status }
```

### 3.3 校验（发布前置）

新增 `ContentSchemaValidator`（Java 规则，不新增/修改 `resources/schemas/`）：

| 类型 | 必填/闭集校验 |
|---|---|
| KG | code 前缀与 type 一致（cd_/psy_/cc_/st_）；name 1–64；payload 为对象且 ≤4000 字符；按 type 校验 definition/socraticTemplates、summary/microAction/aboutTags、situation/unhelpful/helpful、description/whenToUse/steps |
| SCENE | code `^[A-Z][A-Z0-9_]{1,31}$`；title ≤64；description ≤512；difficulties ⊆ MILD/NORMAL/HARD 且非空；persona 四要素与 openingLines；goalDimensions ⊆ LISTEN/BOUNDARY/EMPATHY/CONCESSION；maxTurns 1–50；recommendedFor 命中压力源闭集 |
| EXERCISE | code `^ex_[a-z0-9_]{1,29}$`；name ≤64；applyEmotions 非空；steps 非空且每步含 step/desc；durationMin 1–60 |
| 通用 | status 归一化：1=上架，0/2=下架；未知值报错；字段超长报错 |

输出 `{valid, errors[], warnings[], normalizedPayload}`：errors 阻断发布，warnings 仅提示。预览与发布使用同一校验器。

### 3.4 后端接口

沿用类级 `ADMIN + admin:scene` 权限；全部写操作落审计。

| 接口 | 行为 |
|---|---|
| `GET /admin/content/snapshot/{type}/{code}` | 返回 live 内容的标准快照，供编辑器载入 |
| `GET /admin/content/drafts?type=` | 草稿列表（含快速校验结果） |
| `GET /admin/content/drafts/{type}/{code}` | 单条草稿 |
| `PUT /admin/content/drafts/{type}/{code}` | 保存草稿（payload + 可选 baseRevisionId/note；不要求 payload 已合法） |
| `DELETE /admin/content/drafts/{type}/{code}` | 丢弃草稿 |
| `POST /admin/content/drafts/{type}/{code}/preview` | 校验并返回 errors/warnings/normalizedPayload/conflict |
| `POST /admin/content/drafts/{type}/{code}/publish` | 校验 + 冲突检查 → 写 live + revision + bump + 删草稿 |
| `GET /admin/content/revisions/{type}/{code}` | 历史版本列表（id/revisionNo/action/source/publishedBy/publishedAt/summary） |
| `POST /admin/content/revisions/{type}/{code}/{revisionId}/rollback` | 校验历史快照 → 写 live + 新 ROLLBACK revision + bump |

- 既有 `PUT /kg|scenes|exercises/{code}` 保留，但改为走同一「校验 → 写 live → 生成 revision → bump」管线；回执新增 `revisionNo`。
- 草稿 `baseRevisionId` 与最新 revision 不一致时，publish 返回 `CONFLICT(2003)`，提示重新载入最新版本；不静默覆盖。
- 审计动作：`CONTENT_DRAFT_SAVE`、`CONTENT_DRAFT_DELETE`、`CONTENT_PUBLISH`、`CONTENT_ROLLBACK`。
- Neo4j 不新增同步入口：继续使用 `content:version` 懒同步；图未启用时行为不变。

### 3.5 管理端

`AdminContentView.vue`：

- 内容条目新增「编辑」：载入标准快照到 JSON 编辑器。
- 编辑器操作：保存草稿、校验预览（errors/warnings/conflict 明示）、发布、丢弃草稿。
- 新增「历史版本」：列出 revisionNo/action/时间/摘要；支持回滚。
- 列表显示「有草稿」标记；上下架仍走既有入口，但同样生成 revision。

## 4. 验收

| 门禁 | 通过条件 |
|---|---|
| 校验 | 四类 KG、场景、练习的合法样例通过；缺字段/超长/闭集越界/前缀不符被 errors 阻断 |
| 草稿 | 保存/读取/列表/删除可用；草稿不改变 live 内容与版本号 |
| 发布 | 仅合法草稿可发布；生成 revision 1..N；live 内容与快照一致；版本号递增 |
| 回滚 | 任意历史版本可回滚；生成 ROLLBACK revision；live 内容恢复；旧快照不合法时拒绝且 live 不变 |
| 冲突 | draft.baseRevisionId 落后于最新 revision 时发布返回 2003 |
| 兼容 | 既有三类 PUT 仍可用，且同样校验/生成 revision |
| 前端 | 编辑→预览→发布→历史→回滚全链路可操作；112+ 例、typecheck、build 绿 |
| 回归 | 后端全量绿；golden 15 场景不变；C guard PASS（C 类零改动） |
| 回滚 | 新增表与接口均可回退；V3 迁移可保留（空表不影响旧代码） |

## 5. 批准状态

- [x] 用户「提交 H-03 并继续 H-04」（2026-11-12）。

## 6. 落地记录

| 项 | 结果 |
|---|---|
| 数据 | 新增 `V3__content_ops.sql`（`content_draft` / `content_revision`），本地 MySQL 已成功迁移至 v3；未改既有表与 `schema.sql` |
| 后端 | `ContentOpsService` 草稿/预览/发布/回滚；`ContentSchemaValidator` 覆盖 KG 四类、场景、练习；`ContentAdminController` 新增 9 个端点并去掉 Repository 依赖（ArchUnit 冻结清单收敛） |
| 契约 | OpenAPI 纯新增 563 行（9 端点 + 6 DTO + `ContentWriteResp.revisionNo`）；前端/小程序生成类型已同步 |
| 管理端 | 内容列表新增「编辑/草稿标记」；编辑器支持保存草稿、校验预览、发布、丢弃、历史版本与回滚 |
| 测试 | 后端 461 例 0 失败（9 跳过）；前端 112 例 + typecheck + build；小程序 20 例 + typecheck + lint |
| 回归 | golden 15 场景字段路径一致；C guard PASS（C 类零改动；仅新增 V3 迁移）；采样噪声已还原 |
| 说明 | 既有三类 PUT 保留并接入同一校验/快照/版本管线；Neo4j 仍由 `content:version` 懒同步，未新增同步入口 |
