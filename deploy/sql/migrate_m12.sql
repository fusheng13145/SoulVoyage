-- M12 增量迁移（对 M11 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m11.sql）。
-- 覆盖：群体看板两张新表 + user_preferences 授权列 + admin:board 权限行。
-- 注：聚合只读明文结构化列（打卡/轨迹/风险级别），本迁移刻意不为看板新增任何密文列或明细表——
--     "看板永远看不到个体"由查询侧无路可达保证，而不是靠约定。

-- ---------- 新表：M12 群体与成员 ----------

CREATE TABLE IF NOT EXISTS support_group (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(48)  NOT NULL COMMENT '群体名（如班级/年级小组）',
  status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 2停用（停用后看板抑制，不毁成员关系）',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_name (name)
) COMMENT='M12 群体（成员由管理端维护，聚合只计入已授权成员）';

CREATE TABLE IF NOT EXISTS support_group_member (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  group_id   BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_group_user (group_id, user_id),
  KEY idx_user (user_id)
) COMMENT='M12 群体成员关系（成员身份≠被看：进聚合还需本人 counselor_board_on 授权）';

-- ---------- 改表：user_preferences（M12 授权开关） ----------

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE user_preferences ADD COLUMN counselor_board_on TINYINT NOT NULL DEFAULT 0 COMMENT 'M12 群体看板授权（显式默认关，开启仅进匿名聚合）'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user_preferences' AND column_name='counselor_board_on');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 权限种子（幂等，同 RbacSeeder 口径） ----------

INSERT IGNORE INTO role_permission (role, permission_code) VALUES ('ADMIN', 'admin:board');
