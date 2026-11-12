-- H-04 内容运营闭环：草稿 + 不可变发布快照（只新增表，不改既有表语义）
-- 说明：C-06 schema.sql 为既有基线事实源；本次仅新增迁移文件，不回写历史 schema。

CREATE TABLE content_draft (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  content_type     VARCHAR(16)   NOT NULL COMMENT 'KG/SCENE/EXERCISE',
  code             VARCHAR(48)   NOT NULL COMMENT '内容编码',
  payload_json     JSON          NOT NULL COMMENT '完整快照草稿（与发布请求同构）',
  base_revision_id BIGINT UNSIGNED NULL COMMENT '草稿基于的发布版本；NULL=首次发布',
  note             VARCHAR(255)  NULL COMMENT '运营备注',
  updated_by       BIGINT UNSIGNED NOT NULL COMMENT '最后编辑管理员',
  updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_content_draft_type_code (content_type, code),
  KEY idx_content_draft_type (content_type)
) COMMENT='H-04 内容运营草稿（未发布，不影响线上）';

CREATE TABLE content_revision (
  id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  content_type       VARCHAR(16)   NOT NULL COMMENT 'KG/SCENE/EXERCISE',
  code               VARCHAR(48)   NOT NULL COMMENT '内容编码',
  revision_no        INT           NOT NULL COMMENT '同一内容内递增，从 1 开始',
  payload_json       JSON          NOT NULL COMMENT '不可变完整快照（可重放/回滚）',
  action             VARCHAR(16)   NOT NULL COMMENT 'PUBLISH/ROLLBACK',
  source_revision_id BIGINT UNSIGNED NULL COMMENT 'ROLLBACK 回滚到的源版本',
  published_by       BIGINT UNSIGNED NOT NULL COMMENT '操作管理员',
  published_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_content_revision_type_code_no (content_type, code, revision_no),
  KEY idx_content_revision_lookup (content_type, code, revision_no)
) COMMENT='H-04 内容发布快照与回滚历史（不可变）';
