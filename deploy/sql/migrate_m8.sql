-- M8 增量迁移（对 M7 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m7.sql）。
-- 覆盖：scene_card 扩列 / simulate_session 分数列 / kg_node 等内容表 / exercise 统一 ex_* 编码。

-- ---------- 改表：scene_card（N1 标签与画像推荐） ----------
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE scene_card ADD COLUMN tags VARCHAR(128) NULL COMMENT 'N1 场景标签，逗号分隔'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='scene_card' AND column_name='tags');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE scene_card ADD COLUMN recommended_for VARCHAR(128) NULL COMMENT 'N1 画像推荐压力源，逗号分隔'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='scene_card' AND column_name='recommended_for');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 改表：simulate_session（C3 训练档案） ----------
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE simulate_session ADD COLUMN avg_score DECIMAL(4,1) NULL COMMENT 'C3 四维均分'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='simulate_session' AND column_name='avg_score');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE simulate_session ADD COLUMN dimension_scores JSON NULL COMMENT 'C3 各维度分（雷达图）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='simulate_session' AND column_name='dimension_scores');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE simulate_session ADD COLUMN weaknesses_enc BLOB NULL COMMENT '✦ C3 弱项摘要，供续练 prompt 注入'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='simulate_session' AND column_name='weaknesses_enc');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- simulate_session.status 增加 INTERRUPTED 为应用层枚举扩展，无需 DDL；刷新注释：
ALTER TABLE simulate_session MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
  COMMENT 'RUNNING/WAITING_USER/FINISHED/ABORTED_RISK/INTERRUPTED';

-- ---------- 新表：内容与知识图谱（N2/N3/N4） ----------
CREATE TABLE IF NOT EXISTS kg_node (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  type         VARCHAR(16)     NOT NULL COMMENT 'DISTORTION/PSY_TOPIC/COMM_CASE/STRENGTH_TECH（单表多型）',
  code         VARCHAR(48)     NOT NULL COMMENT 'kgNodeId：cd_*/psy_*/cc_*/st_*，全局唯一',
  name         VARCHAR(64)     NOT NULL,
  payload_json JSON            NOT NULL COMMENT '内容字段（与 classpath kg/*.json 同源）',
  status       TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 2下架',
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code),
  KEY idx_type_status (type, status)
) COMMENT='知识图谱节点（M8 起 DB 为内容真源，Neo4j 就绪后由本表同步）';

CREATE TABLE IF NOT EXISTS content_meta (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  meta_key   VARCHAR(64)   NOT NULL COMMENT 'content:version / kg:stressor_map 等',
  meta_value TEXT          NOT NULL,
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_key (meta_key)
) COMMENT='内容字典与缓存版本（N4 热更新失效信号）';

CREATE TABLE IF NOT EXISTS user_read_log (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  code       VARCHAR(48)     NOT NULL COMMENT '已读 kg_node.code（psy_*）',
  read_date  DATE            NOT NULL COMMENT '业务日（Asia/Shanghai）',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_code_date (user_id, code, read_date),
  KEY idx_user_date (user_id, read_date)
) COMMENT='N3 阅读记录';

CREATE TABLE IF NOT EXISTS user_favorite (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT UNSIGNED NOT NULL,
  type       VARCHAR(16)     NOT NULL COMMENT 'PSY_TOPIC/REPORT/...',
  ref_code   VARCHAR(48)     NOT NULL COMMENT 'kg_node.code 或资源编号',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_user_type_ref (user_id, type, ref_code)
) COMMENT='N3 收藏入档案';

-- ---------- 改表：exercise 统一 ex_* 编码（N4 前置） ----------
-- 1) exercise_library.code 大写→小写（幂等：已是小写则无行受影响）
UPDATE exercise_library SET code = LOWER(code) WHERE code <> LOWER(code);

-- 2) exercise_record 加 exercise_code 列
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record ADD COLUMN exercise_code VARCHAR(32) NULL COMMENT 'M8: exercise_library.code（ex_*）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='exercise_code');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 回填：旧 BIGINT 外键翻译为 code（仅当旧列还在时执行一次）
SET @s := (SELECT IF(COUNT(*)>0,
  "UPDATE exercise_record r JOIN exercise_library l ON r.exercise_id=l.id
     SET r.exercise_code=LOWER(l.code) WHERE r.exercise_code IS NULL",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='exercise_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 兜底：库里没有对应行的历史数据不丢弃，落到通用着陆练习
UPDATE exercise_record SET exercise_code='ex_54321' WHERE exercise_code IS NULL;

-- 4) 旧唯一键（挂在 exercise_id 上）换新键（挂在 exercise_code 上）
SET @s := (SELECT IF(COUNT(*)>0,
  "ALTER TABLE exercise_record DROP INDEX uk_user_ex_date",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='exercise_record'
    AND index_name='uk_user_ex_date' AND column_name='exercise_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE exercise_record MODIFY COLUMN exercise_code VARCHAR(32) NOT NULL COMMENT 'M8: exercise_library.code（ex_*）',
     ADD UNIQUE KEY uk_user_ex_date (user_id, exercise_code, check_date)",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='exercise_record'
    AND index_name='uk_user_ex_date' AND column_name='exercise_code');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5) 删除旧列与其索引（幂等：列已删则跳过）
SET @s := (SELECT IF(COUNT(*)>0,
  "ALTER TABLE exercise_record DROP INDEX idx_exercise",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='exercise_record'
    AND index_name='idx_exercise' AND column_name='exercise_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := (SELECT IF(COUNT(*)>0,
  "ALTER TABLE exercise_record DROP COLUMN exercise_id",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='exercise_record' AND column_name='exercise_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 种子移交：内容表清空后由应用 ContentDataSeeder 重新导入 ----------
-- （dev 库如需重建内容：TRUNCATE exercise_library; TRUNCATE scene_card; 重启应用即可。）
