-- M5 增量迁移（对 M4 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否 ADD COLUMN，可安全重复执行。
-- （MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，故用 PREPARE 绕开）

-- S1 危机生命周期
ALTER TABLE `user`
  MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 2冻结 3注销(冷静期内可撤回, 期满销毁密钥)';

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN crisis_state VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '危机生命周期 NORMAL/CRISIS/COOLING/REVIEW'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='crisis_state');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN crisis_started_at DATETIME(3) NULL COMMENT '本轮危机链首次 HIGH 判定时间'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='crisis_started_at');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN crisis_ends_at DATETIME(3) NULL COMMENT 'CRISIS=强干预到期时间; COOLING=进入冷却时间(新风险事件水位)'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='crisis_ends_at');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- S2 注销即遗忘
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN deletion_requested_at DATETIME(3) NULL COMMENT '注销申请时间(7天冷静期起点)'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='deletion_requested_at');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 协议字段（schema.sql 既有定义，dev 老库可能缺失）
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN policy_version VARCHAR(16) NULL COMMENT '最近一次同意的协议版本'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='policy_version');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN agreed_policy_at DATETIME(3) NULL COMMENT '用户协议/免责声明同意时间'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='agreed_policy_at');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE risk_event ADD COLUMN needs_review TINYINT NOT NULL DEFAULT 0 COMMENT '否定/引文降级命中，建议人工复核(S1)'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='risk_event' AND column_name='needs_review');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS crisis_lifecycle (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  event_id     BIGINT UNSIGNED NULL COMMENT '触发风险事件（可空：巡检/复核结案）',
  from_state   VARCHAR(16)     NOT NULL COMMENT 'NORMAL/CRISIS/COOLING/REVIEW',
  to_state     VARCHAR(16)     NOT NULL,
  reason       VARCHAR(64)     NOT NULL COMMENT 'HIGH_DETECTED/RE_ENTRY/COOLDOWN_EXPIRED/AUTO_RECOVERED/TWO_ENTRIES_FORCED_REVIEW/ADMIN_CLOSED/...',
  operator_id  BIGINT UNSIGNED NULL COMMENT 'NULL=系统自动（巡检/降级）',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_to_state (to_state, created_at)
) COMMENT='危机状态机迁移留痕（append-only，crisis_end 即 to_state=NORMAL 行）';

-- C1 日记本
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE report ADD COLUMN stale TINYINT NOT NULL DEFAULT 0 COMMENT '源日记被改/删，报告过期待重分析(C1)'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='report' AND column_name='stale');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

CREATE TABLE IF NOT EXISTS diary (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL,
  content_enc      BLOB            NOT NULL COMMENT '✦ 日记原文密文',
  mood_self_rating TINYINT         NULL COMMENT '用户自评心情 1-5',
  record_date      DATE            NOT NULL,
  task_id          BIGINT UNSIGNED NULL COMMENT '关联 DIARY_PIPELINE 任务',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at       DATETIME(3)     NULL,
  KEY idx_user_date (user_id, record_date),
  KEY idx_task (task_id)
) COMMENT='情绪日记（原文必须加密）';

CREATE TABLE IF NOT EXISTS draft (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  kind        VARCHAR(16)     NOT NULL DEFAULT 'DIARY' COMMENT '服务端草稿类型（当前仅 DIARY）',
  content_enc BLOB            NOT NULL COMMENT '✦ 草稿原文密文',
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_kind (user_id, kind)
) COMMENT='编辑器服务端草稿（30s 自动保存，换端不丢；提交成功后清除）';

-- data_key 唯一约束兜底（DEK 并发建钥修复；若历史脏数据已有重复 (owner,version) 行会失败，需先人工清重）
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE data_key ADD UNIQUE KEY uk_owner_ver (owner_user_id, version)",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='data_key' AND index_name='uk_owner_ver');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
