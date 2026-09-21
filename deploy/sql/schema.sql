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
  source_type     VARCHAR(16)     NOT NULL COMMENT 'DIARY/SELF_RATING/SIMULATE/CHAT(M7 漫聊分段消化)',
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
  source           VARCHAR(8)      NOT NULL DEFAULT 'TEXT' COMMENT 'M11 输入源：TEXT 手写 / VOICE 语音转写（音频本身不留存）',
  voice_duration_ms INT            NULL COMMENT '语音录音时长（毫秒），手写为 NULL',
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
  starred     TINYINT         NOT NULL DEFAULT 0 COMMENT 'L2 报表批注：用户收藏，只影响本账号',
  feedback    VARCHAR(16)     NULL COMMENT 'L2 评价：USEFUL/UNSURE/UNHELPFUL，NULL=未评',
  feedback_note_enc BLOB      NULL COMMENT '✦ L2 反馈原话密文（用户输入）',
  feedback_at DATETIME(3)     NULL,
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
  tags          VARCHAR(128)    NULL COMMENT 'N1 场景标签，逗号分隔：宿舍/师生/求职/友谊/亲密/家庭/自我',
  recommended_for VARCHAR(128)  NULL COMMENT 'N1 画像推荐：命中的压力源，逗号分隔（与 KG 压力源闭集同源）',
  status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) COMMENT='人际训练场景卡（M8 起 DB 真源，JSON 仅作初始种子）';

CREATE TABLE simulate_session (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL,
  scene_code       VARCHAR(32)     NOT NULL,
  difficulty       VARCHAR(8)      NOT NULL DEFAULT 'NORMAL',
  status           VARCHAR(16)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/WAITING_USER/FINISHED/ABORTED_RISK/INTERRUPTED',
  total_turns      INT             NOT NULL DEFAULT 0,
  avg_score        DECIMAL(4,1)    NULL COMMENT 'C3 复盘四维均分（0-100 的一位小数）',
  dimension_scores JSON            NULL COMMENT 'C3 各维度分 {LISTEN:72,...}，雷达图数据源',
  weaknesses_enc   BLOB            NULL COMMENT '✦ C3 弱项维度+评语摘要，供弱项 prompt 注入',
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
  code           VARCHAR(32)     NOT NULL COMMENT 'M8 统一小写 ex_*（与 Agent 闭集 id 同名，不再双轨）',
  name           VARCHAR(64)     NOT NULL,
  apply_emotions JSON            NOT NULL COMMENT '适用情绪/场景枚举列表',
  steps_json     JSON            NOT NULL COMMENT '引导步骤（规则层内容，不由LLM生成）',
  duration_min   TINYINT         NOT NULL DEFAULT 5,
  status         TINYINT         NOT NULL DEFAULT 1,
  UNIQUE KEY uk_code (code)
) COMMENT='自助练习库（闭集；M8 起 DB 真源）';

CREATE TABLE exercise_record (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  exercise_code VARCHAR(32)   NOT NULL COMMENT 'M8：exercise_library.code（ex_*），替代原自增 id 外键',
  plan_report_id BIGINT UNSIGNED NULL COMMENT '来源疏导方案报告',
  plan_id     BIGINT UNSIGNED NULL COMMENT 'G4 挂真实成长计划',
  plan_item_seq INT             NULL COMMENT '计划内条目序号（完成时回写 plan_item）',
  scheduled_date DATE           NULL COMMENT 'C4 排期日（来自 plan_item.scheduled_date）',
  check_date  DATE            NOT NULL COMMENT 'O1 业务归属日（Asia/Shanghai），同日防重复',
  duration_actual INT           NULL COMMENT 'C4 实际跟练时长（秒），伪流时代由前端计时上报',
  completed   TINYINT         NOT NULL DEFAULT 0,
  feedback    VARCHAR(255)    NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_exercise (exercise_code),
  KEY idx_plan (plan_id),
  UNIQUE KEY uk_user_ex_date (user_id, exercise_code, check_date)
) COMMENT='练习打卡（M7：同日同练习幂等；M8：按 code 关联）';

-- ---------- M7 · 树洞漫聊（C0） ----------

CREATE TABLE companion_session (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  chat_date    DATE            NOT NULL COMMENT 'O1 业务日；分段键=自然日+30min 静默',
  segment_no   INT             NOT NULL DEFAULT 1 COMMENT '当日第几段',
  status       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SEALED(分段封口待消化)/DIGESTED(已进 COMPANION_PIPELINE)',
  turns        INT             NOT NULL DEFAULT 0,
  last_turn_at DATETIME(3)     NULL COMMENT '静默计时基准（30min 无新回合即封段）',
  excl_flag    TINYINT         NOT NULL DEFAULT 0 COMMENT '会话级排除分析（companion_analysis_on 关闭时新建会话置 1）',
  digested_at  DATETIME(3)     NULL,
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_date_seg (user_id, chat_date, segment_no),
  KEY idx_user_status (user_id, status)
) COMMENT='漫聊会话分段';

CREATE TABLE companion_turn (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  session_id    BIGINT UNSIGNED NOT NULL,
  turn_no       INT             NOT NULL,
  user_text_enc BLOB            NOT NULL COMMENT '✦ 用户消息密文（同 simulate 规范）',
  ai_text       TEXT            NOT NULL COMMENT '陪伴回复（平台生成明文）',
  mood_tag      VARCHAR(16)     NULL COMMENT '本轮情绪响应策略档位 FOLLOW/LOW_ENERGY/WARM_lift…',
  risk_hit      TINYINT         NOT NULL DEFAULT 0 COMMENT '逐轮 RiskRules 命中（HIGH 当轮拦截）',
  no_analyze    TINYINT         NOT NULL DEFAULT 0 COMMENT '「这句别分析」：本条排除画像管道',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_session_turn (session_id, turn_no)
) COMMENT='漫聊逐轮记录';

-- ---------- M7 · 成长陪伴（G 系列） ----------

CREATE TABLE mood_check_in (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  check_date   DATE            NOT NULL COMMENT 'O1 业务归属日，支持补写/修改',
  rating       TINYINT         NULL COMMENT '整体心情 1-5（可空：只选表情不打分）',
  emotion_code VARCHAR(16)     NOT NULL COMMENT '16 情绪盘闭集 code（与前端 EMOTIONS 同源）',
  energy       TINYINT         NULL COMMENT '能量值 1-5',
  note_enc     BLOB            NULL COMMENT '✦ 可选一句话密文',
  made_up      TINYINT         NOT NULL DEFAULT 0 COMMENT 'G3 补签救济（每自然月 1 次）',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_check (user_id, check_date)
) COMMENT='G1 每日心情打卡（每日一条，可改）';

CREATE TABLE growth_plan (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL,
  source_report_id BIGINT UNSIGNED NULL COMMENT '来源 SUPPORT 报告',
  days             TINYINT         NOT NULL DEFAULT 3 COMMENT '3/5/7',
  title            VARCHAR(128)    NOT NULL,
  status           VARCHAR(16)     NOT NULL DEFAULT 'ACTION' COMMENT 'ACTION/DONE/DROPPED',
  items_json       JSON            NULL COMMENT '生成时快照（权威逐日数据在 plan_item）',
  start_date       DATE            NOT NULL,
  end_date         DATE            NOT NULL,
  summary_done     TINYINT         NOT NULL DEFAULT 0 COMMENT '到期小结是否已生成',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_status (user_id, status),
  KEY idx_user_end (user_id, end_date)
) COMMENT='G4 成长计划（短期减压方案的可执行载体）';

CREATE TABLE plan_item (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  plan_id        BIGINT UNSIGNED NOT NULL,
  seq            INT             NOT NULL COMMENT '全局序号，从 1（按 scheduled_date+日内次序）',
  exercise_code  VARCHAR(32)     NOT NULL,
  guidance       VARCHAR(255)    NULL COMMENT '一句引导语',
  scheduled_date DATE            NOT NULL,
  done_at        DATETIME(3)     NULL,
  feedback       VARCHAR(255)    NULL,
  UNIQUE KEY uk_plan_seq (plan_id, seq),
  KEY idx_plan_date (plan_id, scheduled_date)
) COMMENT='G4 计划逐日条目';

CREATE TABLE notification (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  kind       VARCHAR(16)     NOT NULL COMMENT 'SYSTEM/PLAN/ACHIEVEMENT/LETTER/RISK_CARE',
  dedup_key  VARCHAR(64)     NULL COMMENT '幂等键（如 checkin:2026-09-19），定时扫描重跑不重复',
  title      VARCHAR(64)     NOT NULL,
  body       VARCHAR(500)    NOT NULL,
  link       VARCHAR(128)    NULL COMMENT '应用内跳转路径（实现增列：点击直达）',
  read_at    DATETIME(3)     NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_dedup (user_id, dedup_key),
  KEY idx_user_time (user_id, created_at),
  KEY idx_user_unread (user_id, read_at)
) COMMENT='G5 站内通知（无短信/邮件，隐私最小化）';

CREATE TABLE user_preferences (
  id                     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id                BIGINT UNSIGNED NOT NULL,
  theme                  VARCHAR(8)      NOT NULL DEFAULT 'system' COMMENT 'system/light/dark',
  checkin_reminder_on    TINYINT         NOT NULL DEFAULT 0 COMMENT '默认关，首次开启引导',
  reminder_time          CHAR(5)         NOT NULL DEFAULT '20:00' COMMENT 'HH:MM 业务时区',
  plan_reminder_on       TINYINT         NOT NULL DEFAULT 0,
  letter_on              TINYINT         NOT NULL DEFAULT 1,
  haptic_on              TINYINT         NOT NULL DEFAULT 1,
  companion_analysis_on  TINYINT         NOT NULL DEFAULT 1 COMMENT 'C0 漫聊参与情绪分析总开关',
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user (user_id)
) COMMENT='G5 用户偏好（服务端真源，前端 localStorage 降级为缓存）';

CREATE TABLE user_achievement (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  code         VARCHAR(32)     NOT NULL COMMENT 'FIRST_DIARY/FIRST_A_GRADE/STREAK_7/ALL_EXERCISES/NO_REPEAT_DISTORTION/STREAK_30/COMPANION_OPENED...',
  unlocked_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_code (user_id, code)
) COMMENT='G3 成就徽章（全部指向自我关照行为，无分数竞争）';

CREATE TABLE growth_letter (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL,
  stat_week    CHAR(8)         NOT NULL COMMENT 'ISO 周，如 2026-W38',
  content_enc  BLOB            NOT NULL COMMENT '✦ 第二人称信正文密文',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_week (user_id, stat_week)
) COMMENT='G6 成长来信（每周一个人格化周报）';

-- ---------- M8 · 内容与知识图谱（N1–N4） ----------

CREATE TABLE kg_node (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  type         VARCHAR(16)     NOT NULL COMMENT 'DISTORTION/PSY_TOPIC/COMM_CASE/STRENGTH_TECH（单表多型）',
  code         VARCHAR(48)     NOT NULL COMMENT 'kgNodeId：cd_*/psy_*/cc_*/st_*，全局唯一',
  name         VARCHAR(64)     NOT NULL COMMENT '误区名/科普标题/案例标题/技巧名',
  payload_json JSON            NOT NULL COMMENT '内容字段（definition/emotions/socraticTemplates/summary/aboutTags/…）',
  status       TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code),
  KEY idx_type_status (type, status)
) COMMENT='知识图谱节点（Neo4j 就绪前 DB 即内容真源，词条与 deploy/neo4j/seed.cypher 同源）';

CREATE TABLE content_meta (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  meta_key   VARCHAR(64)   NOT NULL COMMENT 'content:version / kg:stressor_map 等',
  meta_value TEXT          NOT NULL,
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_key (meta_key)
) COMMENT='内容字典与缓存版本（N4 热更新失效信号）';

CREATE TABLE user_read_log (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  code       VARCHAR(48)     NOT NULL COMMENT '已读 kg_node.code（psy_*）',
  read_date  DATE            NOT NULL COMMENT '业务日（Asia/Shanghai），供每日一读去重',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_code_date (user_id, code, read_date),
  KEY idx_user_date (user_id, read_date)
) COMMENT='N3 阅读记录';

CREATE TABLE user_favorite (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  type       VARCHAR(16)     NOT NULL COMMENT 'PSY_TOPIC/REPORT/...',
  ref_code   VARCHAR(48)     NOT NULL COMMENT 'kg_node.code 或资源编号',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_type_ref (user_id, type, ref_code)
) COMMENT='N3 收藏入档案';

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

CREATE TABLE export_record (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  kind       VARCHAR(24)     NOT NULL COMMENT 'ARCHIVE(打印页)/PERSONAL_DATA(S2 全量)',
  status     VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/EXPIRED',
  file_ref   VARCHAR(26)     NOT NULL COMMENT '一次性快照号（ULID），内容不落库',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  claimed_at DATETIME(3)     NULL,
  KEY idx_user_kind (user_id, kind, created_at)
) COMMENT='A5 导出留痕';

-- ---------- 种子数据 ----------
-- M8 起：exercise_library / scene_card / kg_node 不再写死 SQL 种子，
-- 应用启动时由 ContentDataSeeder 从 classpath JSON（exercises/scenes/kg）导入空表，
-- 表非空则跳过——JSON 降级为种子源，运行时以 DB 为真源（N4 热更新）。

INSERT INTO role_permission (role, permission_code) VALUES
  ('USER','task:submit'), ('USER','diary:write'), ('USER','simulate:play'),
  ('USER','report:read'), ('USER','archive:export'), ('USER','account:delete'),
  ('ADMIN','task:submit'), ('ADMIN','admin:task'), ('ADMIN','admin:scene'),
  ('ADMIN','admin:audit'), ('ADMIN','admin:risk:view'), ('ADMIN','admin:user');
