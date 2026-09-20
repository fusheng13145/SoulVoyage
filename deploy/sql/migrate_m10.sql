-- M10 增量迁移（对 M9 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m8.sql）。
-- 覆盖：report 表 L2 批注列（收藏 + 评价 + 反馈原话密文）。

-- ---------- 改表：report（L2 报表批注） ----------
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE report ADD COLUMN starred TINYINT NOT NULL DEFAULT 0 COMMENT 'L2 报表批注：用户收藏，只影响本账号'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='report' AND column_name='starred');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE report ADD COLUMN feedback VARCHAR(16) NULL COMMENT 'L2 评价：USEFUL/UNSURE/UNHELPFUL，NULL=未评'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='report' AND column_name='feedback');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE report ADD COLUMN feedback_note_enc BLOB NULL COMMENT '✦ L2 反馈原话密文（用户输入）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='report' AND column_name='feedback_note_enc');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE report ADD COLUMN feedback_at DATETIME(3) NULL",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='report' AND column_name='feedback_at');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
