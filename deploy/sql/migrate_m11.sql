-- M11 增量迁移（对 M10 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m10.sql）。
-- 覆盖：diary 表 M11 输入源列（TEXT/VOICE 溯源标记 + 录音时长）。
-- 注：语音日记刻意不落任何音频列——音频只在内存里过手一次，转写文本进既有的 content_enc。

-- ---------- 改表：diary（M11 语音日记输入源） ----------
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE diary ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'TEXT' COMMENT 'M11 输入源：TEXT 手写 / VOICE 语音转写（音频本身不留存）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='diary' AND column_name='source');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE diary ADD COLUMN voice_duration_ms INT NULL COMMENT '语音录音时长（毫秒），手写为 NULL'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='diary' AND column_name='voice_duration_ms');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
