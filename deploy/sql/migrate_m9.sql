-- M9 增量迁移（对 M8 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m8.sql）。

-- ---------- 新表：export_record（A5 导出留痕） ----------
CREATE TABLE IF NOT EXISTS export_record (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  kind       VARCHAR(24)     NOT NULL COMMENT 'ARCHIVE(打印页)/PERSONAL_DATA(S2 全量)',
  status     VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/EXPIRED',
  file_ref   VARCHAR(26)     NOT NULL COMMENT '一次性快照号（ULID），内容不落库',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  claimed_at DATETIME(3)     NULL,
  KEY idx_user_kind (user_id, kind, created_at)
) COMMENT='A5 导出留痕';

-- ---------- A4 权限位：admin:user（存量库补种，UK 幂等） ----------
INSERT IGNORE INTO role_permission (role, permission_code) VALUES ('ADMIN','admin:user');
