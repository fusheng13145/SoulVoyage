-- M7 增量迁移（对 M6 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m5.sql）。

-- ---------- 改表：exercise_record（C4/G4） ----------
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN plan_id BIGINT UNSIGNED NULL COMMENT 'G4 挂真实成长计划'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='plan_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN plan_item_seq INT NULL COMMENT '计划内条目序号'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='plan_item_seq');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN scheduled_date DATE NULL COMMENT 'C4 排期日'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='scheduled_date');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN check_date DATE NULL COMMENT 'O1 业务归属日，同日防重复'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='check_date');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN duration_actual INT NULL COMMENT 'C4 实际跟练时长（秒）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='duration_actual');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 存量行回填归属日（按 UTC 创建日近似，业务日语义自 M7 新写入起精确）
UPDATE exercise_record SET check_date = DATE(created_at) WHERE check_date IS NULL;
ALTER TABLE exercise_record MODIFY COLUMN check_date DATE NOT NULL COMMENT 'O1 业务归属日，同日防重复';

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD UNIQUE KEY uk_user_ex_date (user_id, exercise_id, check_date)",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND index_name='uk_user_ex_date');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD KEY idx_plan (plan_id)",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND index_name='idx_plan');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 新表：树洞漫聊（C0） ----------
CREATE TABLE IF NOT EXISTS companion_session (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  chat_date    DATE            NOT NULL COMMENT 'O1 业务日；分段键=自然日+30min 静默',
  segment_no   INT             NOT NULL DEFAULT 1 COMMENT '当日第几段',
  status       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SEALED/DIGESTED',
  turns        INT             NOT NULL DEFAULT 0,
  last_turn_at DATETIME(3)     NULL COMMENT '静默计时基准（30min 无新回合即封段）',
  excl_flag    TINYINT         NOT NULL DEFAULT 0 COMMENT '会话级排除分析',
  digested_at  DATETIME(3)     NULL,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_date_seg (user_id, chat_date, segment_no),
  KEY idx_user_status (user_id, status)
) COMMENT='漫聊会话分段';

CREATE TABLE IF NOT EXISTS companion_turn (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  session_id    BIGINT UNSIGNED NOT NULL,
  turn_no       INT             NOT NULL,
  user_text_enc BLOB            NOT NULL COMMENT '✦ 用户消息密文（同 simulate 规范）',
  ai_text       TEXT            NOT NULL COMMENT '陪伴回复（平台生成明文）',
  mood_tag      VARCHAR(16)     NULL COMMENT '情绪响应策略档位',
  risk_hit      TINYINT         NOT NULL DEFAULT 0 COMMENT '逐轮 RiskRules 命中',
  no_analyze    TINYINT         NOT NULL DEFAULT 0 COMMENT '「这句别分析」',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_session_turn (session_id, turn_no)
) COMMENT='漫聊逐轮记录';

-- ---------- 新表：成长陪伴（G1–G6） ----------
CREATE TABLE IF NOT EXISTS mood_check_in (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  check_date   DATE            NOT NULL COMMENT 'O1 业务归属日，支持补写/修改',
  rating       TINYINT         NULL COMMENT '整体心情 1-5',
  emotion_code VARCHAR(16)     NOT NULL COMMENT '16 情绪盘闭集 code',
  energy       TINYINT         NULL COMMENT '能量值 1-5',
  note_enc     BLOB            NULL COMMENT '✦ 可选一句话密文',
  made_up      TINYINT         NOT NULL DEFAULT 0 COMMENT 'G3 补签救济（每自然月 1 次）',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_check (user_id, check_date)
) COMMENT='G1 每日心情打卡';

CREATE TABLE IF NOT EXISTS growth_plan (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL,
  source_report_id BIGINT UNSIGNED NULL COMMENT '来源 SUPPORT 报告',
  days             TINYINT         NOT NULL DEFAULT 3 COMMENT '3/5/7',
  title            VARCHAR(128)    NOT NULL,
  status           VARCHAR(16)     NOT NULL DEFAULT 'ACTION' COMMENT 'ACTION/DONE/DROPPED',
  items_json       JSON            NULL,
  start_date       DATE            NOT NULL,
  end_date         DATE            NOT NULL,
  summary_done     TINYINT         NOT NULL DEFAULT 0 COMMENT '到期小结是否已生成',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_status (user_id, status),
  KEY idx_user_end (user_id, end_date)
) COMMENT='G4 成长计划';

CREATE TABLE IF NOT EXISTS plan_item (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  plan_id        BIGINT UNSIGNED NOT NULL,
  seq            INT             NOT NULL,
  exercise_code  VARCHAR(32)     NOT NULL,
  guidance       VARCHAR(255)    NULL COMMENT '一句引导语',
  scheduled_date DATE            NOT NULL,
  done_at        DATETIME(3)     NULL,
  feedback       VARCHAR(255)    NULL,
  UNIQUE KEY uk_plan_seq (plan_id, seq),
  KEY idx_plan_date (plan_id, scheduled_date)
) COMMENT='G4 计划逐日条目';

CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  kind       VARCHAR(16)     NOT NULL COMMENT 'SYSTEM/PLAN/ACHIEVEMENT/LETTER/RISK_CARE',
  dedup_key  VARCHAR(64)     NULL COMMENT '幂等键，定时扫描重跑不重复',
  title      VARCHAR(64)     NOT NULL,
  body       VARCHAR(500)    NOT NULL,
  link       VARCHAR(128)    NULL COMMENT '应用内跳转路径（实现增列）',
  read_at    DATETIME(3)     NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_dedup (user_id, dedup_key),
  KEY idx_user_time (user_id, created_at),
  KEY idx_user_unread (user_id, read_at)
) COMMENT='G5 站内通知';

CREATE TABLE IF NOT EXISTS user_preferences (
  id                     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id                BIGINT UNSIGNED NOT NULL,
  theme                  VARCHAR(8)      NOT NULL DEFAULT 'system',
  checkin_reminder_on    TINYINT         NOT NULL DEFAULT 0,
  reminder_time          CHAR(5)         NOT NULL DEFAULT '20:00',
  plan_reminder_on       TINYINT         NOT NULL DEFAULT 0,
  letter_on              TINYINT         NOT NULL DEFAULT 1,
  haptic_on              TINYINT         NOT NULL DEFAULT 1,
  companion_analysis_on  TINYINT         NOT NULL DEFAULT 1 COMMENT 'C0 漫聊参与情绪分析总开关',
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user (user_id)
) COMMENT='G5 用户偏好';

CREATE TABLE IF NOT EXISTS user_achievement (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  code         VARCHAR(32)     NOT NULL,
  unlocked_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_code (user_id, code)
) COMMENT='G3 成就徽章';

CREATE TABLE IF NOT EXISTS growth_letter (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  stat_week    CHAR(8)         NOT NULL,
  content_enc  BLOB            NOT NULL COMMENT '✦ 第二人称信正文密文',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_week (user_id, stat_week)
) COMMENT='G6 成长来信';
