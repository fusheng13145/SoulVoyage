-- ============================================================
-- 心屿漫行 SoulVoyage — MySQL 8.x Schema v1.0
-- 约定：
--   1. 所有表 InnoDB + utf8mb4；主键 BIGINT AUTO_INCREMENT。
--   2. 字段注释含 ✦ 的（列名后缀 _enc）为应用层 AES-256-GCM 密文，
--      存储格式：[1B keyVersion][12B IV][ciphertext][16B tag]，类型 BLOB。
--   3. 业务表统一软删除 deleted_at（NULL=未删除）。
--   4. 时间统一 DATETIME(3)，由应用写入 UTC。
-- 执行：先以 root 创建库与账号（见文件头），再导入本文件。
--   Windows 下必须指定客户端字符集：mysql -u root -p --default-character-set=utf8mb4 < schema.sql
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS soulvoyage DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'soulvoyage'@'localhost' IDENTIFIED BY 'CHANGE_ME_local_dev';
GRANT ALL PRIVILEGES ON soulvoyage.* TO 'soulvoyage'@'localhost';
FLUSH PRIVILEGES;
USE soulvoyage;

-- ---------- 用户与权限 ----------

CREATE TABLE `user` (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL COMMENT '登录名',
  nickname      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  phone_enc     BLOB         NULL COMMENT '✦ 手机号密文',
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2冻结 3注销(冷静期内可撤回, 期满销毁密钥)',
  crisis_state  VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '危机生命周期 NORMAL/CRISIS/COOLING/REVIEW',
  crisis_started_at DATETIME(3) NULL COMMENT '本轮危机链首次 HIGH 判定时间',
  crisis_ends_at    DATETIME(3) NULL COMMENT 'CRISIS=强干预到期时间; COOLING=进入冷却时间(新风险事件水位)',
  policy_version VARCHAR(16)  NULL COMMENT '最近一次同意的协议版本',
  deletion_requested_at DATETIME(3) NULL COMMENT '注销申请时间(7天冷静期起点)',
  agreed_policy_at DATETIME(3) NULL COMMENT '用户协议/免责声明同意时间',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3)  NULL,
  UNIQUE KEY uk_username (username)
) COMMENT='用户';

CREATE TABLE role_permission (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  role            VARCHAR(16) NOT NULL,
  permission_code VARCHAR(64) NOT NULL COMMENT '如 task:submit, admin:audit',
  UNIQUE KEY uk_role_perm (role, permission_code)
) COMMENT='RBAC 权限';

-- ---------- 会话与任务（调度中心） ----------

CREATE TABLE user_session (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT UNSIGNED NOT NULL,
  scene_type     VARCHAR(32)  NOT NULL COMMENT 'DIARY/SIMULATE/SUPPORT/ARCHIVE',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1活跃 2结束 3过期',
  last_active_at DATETIME(3)  NOT NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_active (user_id, status, last_active_at)
) COMMENT='会话';

CREATE TABLE task_instance (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  task_no      CHAR(26)        NOT NULL COMMENT 'ULID 对外暴露的任务号',
  user_id      BIGINT UNSIGNED NOT NULL,
  session_id   BIGINT UNSIGNED NULL,
  pipeline_code VARCHAR(32)    NOT NULL COMMENT 'DIARY_PIPELINE 等',
  status       VARCHAR(24)     NOT NULL COMMENT 'PENDING/RUNNING/WAITING_USER/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED/EXPIRED',
  priority     TINYINT         NOT NULL DEFAULT 5 COMMENT '1最高(危机) 9最低',
  client_req_id VARCHAR(64)    NULL COMMENT '提交幂等键',
  error_msg    VARCHAR(512)    NULL,
  started_at   DATETIME(3)     NULL,
  finished_at  DATETIME(3)     NULL,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_no (task_no),
  UNIQUE KEY uk_client_req (user_id, client_req_id),
  KEY idx_user_status (user_id, status),
  KEY idx_pipeline_time (pipeline_code, created_at)
) COMMENT='任务实例';

CREATE TABLE task_step_log (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  task_id    BIGINT UNSIGNED NOT NULL,
  step_seq   INT             NOT NULL COMMENT '步骤序号，从1',
  agent_code VARCHAR(32)     NOT NULL COMMENT 'EMOTION/TRACE/SIMULATE/SUPPORT/RISK_ARCHIVE',
  step_code  VARCHAR(48)     NOT NULL COMMENT '流水线内步骤标识',
  status     VARCHAR(16)     NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED/DEGRADED',
  attempt    TINYINT         NOT NULL DEFAULT 1,
  cost_ms    INT             NULL,
  tokens_in  INT             NULL,
  tokens_out INT             NULL,
  llm_calls  TINYINT         NOT NULL DEFAULT 0,
  model      VARCHAR(64)     NULL,
  error_msg  VARCHAR(512)    NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_step_attempt (task_id, step_seq, attempt)
) COMMENT='步骤状态机流转日志（业务级 tracing）';

CREATE TABLE agent_message (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  message_no CHAR(26)        NOT NULL COMMENT 'ULID',
  task_id    BIGINT UNSIGNED NOT NULL,
  step_seq   INT             NOT NULL,
  from_agent VARCHAR(32)     NOT NULL COMMENT 'ORCHESTRATOR/EMOTION/...',
  to_agent   VARCHAR(32)     NOT NULL,
  msg_type   VARCHAR(16)     NOT NULL COMMENT 'REQUEST/MIDDLE_RESULT/FINAL/ERROR',
  payload_enc BLOB           NOT NULL COMMENT '✦ 结构化中间结果密文 JSON',
  context_ref VARCHAR(128)   NULL COMMENT '大对象 Redis 引用键',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_msg_no (message_no),
  KEY idx_task_step (task_id, step_seq) COMMENT '按 task_id+step_seq 排序可完整重放一次执行'
) COMMENT='Agent 间结构化消息（append-only）';

-- ---------- 情绪与画像 ----------

CREATE TABLE emotion_trajectory (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT UNSIGNED NOT NULL,
  record_date     DATE            NOT NULL,
  source_type     VARCHAR(16)     NOT NULL COMMENT 'DIARY/SELF_RATING/SIMULATE',
  source_id       BIGINT UNSIGNED NULL COMMENT '来源记录id',
  primary_emotion VARCHAR(32)     NOT NULL COMMENT '16基础情绪枚举',
  valence         DECIMAL(4,3)    NOT NULL COMMENT '效价 -1.000~1.000',
  intensity       DECIMAL(3,2)    NOT NULL COMMENT '强度 0.00~1.00',
  event_tags      JSON            NULL COMMENT '[{tag,scene,evidence}]',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_date_src (user_id, record_date, source_type, source_id),
  KEY idx_user_date (user_id, record_date) COMMENT '情绪曲线数据源'
) COMMENT='情绪时序点';

CREATE TABLE emotion_profile (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT UNSIGNED NOT NULL,
  stat_week       CHAR(8)         NOT NULL COMMENT 'ISO周 2026-W38',
  stressor_top_json JSON          NULL COMMENT '压力源TopN及频次',
  distortion_top_json JSON        NULL COMMENT '认知误区TopN及频次',
  avg_valence     DECIMAL(4,3)    NULL,
  risk_level      VARCHAR(8)      NOT NULL DEFAULT 'LOW' COMMENT '本周最高风险档位',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_week (user_id, stat_week)
) COMMENT='周维度情感画像';

CREATE TABLE diary (
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

CREATE TABLE draft (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  kind        VARCHAR(16)     NOT NULL DEFAULT 'DIARY' COMMENT '服务端草稿类型（当前仅 DIARY）',
  content_enc BLOB            NOT NULL COMMENT '✦ 草稿原文密文',
  updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_kind (user_id, kind)
) COMMENT='编辑器服务端草稿（30s 自动保存，换端不丢；提交成功后清除）';

-- ---------- 报告与风险 ----------

CREATE TABLE report (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  type        VARCHAR(16)     NOT NULL COMMENT 'TRACE/SIMULATE/SUPPORT',
  task_id     BIGINT UNSIGNED NOT NULL,
  biz_ref_id  BIGINT UNSIGNED NULL COMMENT '如 simulate_session.id',
  title       VARCHAR(128)    NOT NULL DEFAULT '',
  content_enc BLOB            NOT NULL COMMENT '✦ 报告正文密文 JSON',
  risk_level  VARCHAR(8)      NOT NULL DEFAULT 'LOW',
  stale       TINYINT         NOT NULL DEFAULT 0 COMMENT '源日记被改/删，报告过期待重分析(C1)',
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)     NULL,
  KEY idx_user_type (user_id, type, created_at),
  KEY idx_task (task_id)
) COMMENT='AI 生成报告';

CREATE TABLE risk_event (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  level        VARCHAR(8)      NOT NULL COMMENT 'MEDIUM/HIGH',
  trigger_type VARCHAR(32)     NOT NULL COMMENT 'KEYWORD_RULE/LLM_SEMANTIC/TRACE_SIGNAL/SIMULATE_BREAKOUT',
  rule_code    VARCHAR(48)     NULL COMMENT '命中规则编码（规则轨）',
  evidence_ref_enc BLOB        NULL COMMENT '✦ 证据引用（原文定位，非原文）',
  action_taken VARCHAR(64)     NOT NULL COMMENT 'CRISIS_CARD/PROFILE_FLAG/... 仅记录行为',
  task_id      BIGINT UNSIGNED NULL,
  reviewed     TINYINT         NOT NULL DEFAULT 0 COMMENT '管理员人工复盘',
  needs_review TINYINT         NOT NULL DEFAULT 0 COMMENT '否定/引文降级命中，建议人工复核(S1)',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_level (level, created_at)
) COMMENT='风险事件（加密归档）';

CREATE TABLE crisis_lifecycle (
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

-- ---------- 模拟训练 ----------

CREATE TABLE scene_card (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code          VARCHAR(32)     NOT NULL COMMENT 'DORM_CONFLICT 等',
  title         VARCHAR(64)     NOT NULL,
  description   VARCHAR(512)    NOT NULL COMMENT '给用户看的背景',
  difficulties  VARCHAR(32)     NOT NULL DEFAULT 'MILD,NORMAL,HARD' COMMENT '支持难度',
  persona_json  JSON            NOT NULL COMMENT 'NPC人设/动机/底线/情绪触发点/背景',
  goal_dimensions JSON          NOT NULL COMMENT '考察维度:LISTEN/BOUNDARY/EMPATHY/CONCESSION',
  max_turns     INT             NOT NULL DEFAULT 20,
  status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) COMMENT='人际训练场景卡';

CREATE TABLE simulate_session (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL,
  scene_code       VARCHAR(32)     NOT NULL,
  difficulty       VARCHAR(8)      NOT NULL DEFAULT 'NORMAL',
  status           VARCHAR(16)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/WAITING_USER/FINISHED/ABORTED_RISK',
  total_turns      INT             NOT NULL DEFAULT 0,
  npc_state_snap_enc BLOB          NULL COMMENT '✦ NPC 情绪档位/关键事件状态（导演模块快照）',
  report_id        BIGINT UNSIGNED NULL COMMENT '复盘报告',
  started_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      DATETIME(3)     NULL,
  KEY idx_user_time (user_id, started_at)
) COMMENT='模拟训练会话';

CREATE TABLE simulate_turn (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  simulate_id  BIGINT UNSIGNED NOT NULL,
  turn_no      INT             NOT NULL,
  user_text_enc BLOB           NOT NULL COMMENT '✦ 用户发言密文',
  npc_text     TEXT            NOT NULL COMMENT 'NPC 回复（剧情文本，非用户敏感数据）',
  npc_emotion  VARCHAR(16)     NOT NULL DEFAULT 'NEUTRAL' COMMENT '导演档位 NEUTRAL/DISSATISFIED/ESCALATED/SOFTENED',
  state_tag    VARCHAR(32)     NULL COMMENT '关键事件:CONFLICT_UP/BOUNDARY_SET/...',
  crisis_flag  TINYINT         NOT NULL DEFAULT 0 COMMENT '本轮检出剧情外真实危机信号',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sim_turn (simulate_id, turn_no)
) COMMENT='训练逐轮记录';

-- ---------- 练习 ----------

CREATE TABLE exercise_library (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code           VARCHAR(32)     NOT NULL COMMENT 'EX_54321/EX_478_BREATH/...',
  name           VARCHAR(64)     NOT NULL,
  apply_emotions JSON            NOT NULL COMMENT '适用情绪/场景枚举列表',
  steps_json     JSON            NOT NULL COMMENT '引导步骤（规则层内容，不由LLM生成）',
  duration_min   TINYINT         NOT NULL DEFAULT 5,
  status         TINYINT         NOT NULL DEFAULT 1,
  UNIQUE KEY uk_code (code)
) COMMENT='自助练习库（闭集）';

CREATE TABLE exercise_record (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  exercise_id BIGINT UNSIGNED NOT NULL,
  plan_report_id BIGINT UNSIGNED NULL COMMENT '来源疏导方案报告',
  completed   TINYINT         NOT NULL DEFAULT 0,
  feedback    VARCHAR(255)    NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_exercise (exercise_id)
) COMMENT='练习打卡';

-- ---------- 系统与审计 ----------

CREATE TABLE audit_log (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NULL COMMENT '操作者，NULL=系统',
  action     VARCHAR(48)     NOT NULL COMMENT 'LOGIN/EXPORT/DELETE/ADMIN_VIEW_RISK/...',
  target     VARCHAR(128)    NULL COMMENT '资源类型:id',
  ip         VARCHAR(46)     NULL,
  hash_chain CHAR(64)        NOT NULL COMMENT 'SHA256(prev_hash + record)，防篡改',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_action (user_id, action, created_at)
) COMMENT='审计日志（哈希链）';

CREATE TABLE data_key (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  owner_user_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=全局密钥；否则用户级 DEK',
  version           INT             NOT NULL,
  enc_master_key_ref VARBINARY(512) NOT NULL COMMENT 'MK 信封加密后的 DK 密文',
  algo              VARCHAR(32)     NOT NULL DEFAULT 'AES-256-GCM',
  kdf               VARCHAR(32)     NOT NULL DEFAULT 'HKDF-SHA256',
  status            TINYINT         NOT NULL DEFAULT 1 COMMENT '1启用 2仅解密 3已销毁(注销即遗忘)',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  destroyed_at      DATETIME(3)     NULL,
  UNIQUE KEY uk_owner_ver (owner_user_id, version)
) COMMENT='密钥登记（信封加密）';

-- ---------- 种子数据 ----------

INSERT INTO role_permission (role, permission_code) VALUES
  ('USER','task:submit'), ('USER','diary:write'), ('USER','simulate:play'),
  ('USER','report:read'), ('USER','archive:export'), ('USER','account:delete'),
  ('ADMIN','task:submit'), ('ADMIN','admin:task'), ('ADMIN','admin:scene'),
  ('ADMIN','admin:audit'), ('ADMIN','admin:risk:view');

INSERT INTO exercise_library (code, name, apply_emotions, steps_json, duration_min) VALUES
  ('EX_54321','54321 感官着陆','["ANXIETY","FEAR","ACUTE_STRESS"]',
   '[{"step":"看","desc":"说出你看到的 5 样东西"},{"step":"听","desc":"说出你听到的 4 种声音"},{"step":"触","desc":"触摸 3 样物品并描述质感"},{"step":"闻","desc":"辨认 2 种气味"},{"step":"尝","desc":"感受 1 种味道，或做 1 次深呼吸"}]',5),
  ('EX_478_BREATH','4-7-8 呼吸','["HIGH_PRESSURE","INSOMNIA","ANXIETY"]',
   '[{"step":"吸气","desc":"用鼻子吸气 4 秒"},{"step":"屏息","desc":"屏住呼吸 7 秒"},{"step":"呼气","desc":"用嘴缓慢呼气 8 秒"},{"step":"循环","desc":"重复以上循环 4 次"}]',4),
  ('EX_CBT_WRITE','认知书写三栏表','["SADNESS","SHAME","ANXIETY"]',
   '[{"step":"情境","desc":"写下触发情绪的具体事件（只写事实）"},{"step":"想法","desc":"写下当时脑中自动冒出的想法"},{"step":"替代","desc":"像朋友辩护一样，写一个更平衡的想法"},{"step":"重评","desc":"给情绪强度重新打分 0-10"}]',10),
  ('EX_ACTION','行为激活清单','["LOW_MOOD","NUMBNESS"]',
   '[{"step":"选择","desc":"从清单选 1-2 件 5 分钟内可完成的小事"},{"step":"执行","desc":"今天完成它并勾选"},{"step":"记录","desc":"写下完成后的情绪变化"}]',7),
  ('EX_NAME_EMOTION','情绪命名练习','["NUMBNESS","CONFUSED"]',
   '[{"step":"扫描","desc":"闭眼 30 秒感受身体当前的感觉"},{"step":"选择","desc":"从情绪词库选出最接近的 1-2 个词"},{"step":"造句","desc":"用「我感到___，因为___」造一个句子"}]',5);
