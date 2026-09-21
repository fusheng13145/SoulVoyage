-- M14 增量迁移（对 M13 版 dev 库执行；全新库直接跑 schema.sql 即可，无需本文件）
-- 条件式 DDL：先查 information_schema 再决定是否执行，可安全重复跑（风格同 migrate_m12.sql）。
-- 覆盖：user 表一个绑定列 + 它的唯一索引。
-- 注：本里程碑是"多端"，不是"多存储"——小程序复用全部既有表与加密域，所以增量只有身份绑定这一列。
--     wx_openid 明文存放的理由与 username 相同：它是"本应用内对该用户的假名标识"，且必须等值可查才能完成登录；
--     它不属于 ✦ 加密域（✦ 留给日记正文/手机号这类内容型个人信息），注销执行时由应用侧置空。

SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD COLUMN wx_openid VARCHAR(64) NULL COMMENT 'M14 小程序端身份绑定：应用内假名标识，需等值查询故与 username 同类明文（非 ✦ 域），注销执行时置空'",
  "DO 0") FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='wx_openid');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- MySQL 唯一索引允许多行 NULL：未绑定微信的用户不受任何影响
SET @s := (SELECT IF(COUNT(*)=0,
  "ALTER TABLE `user` ADD UNIQUE KEY uk_wx_openid (wx_openid)",
  "DO 0") FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_wx_openid');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 权限种子零新增：小程序不新增一种权限，能登录 Web 的账号在小程序上是同一个人、同一套边界。
