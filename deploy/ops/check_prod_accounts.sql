-- =============================================================================
-- 生产库账号核查（只读）— soul-voyage
-- 用途：上线前确认「有没有不该存在的管理员」「有没有遗留的测试账号」
-- 用法：mysql -h <host> -P <port> -u <readonly_user> -p < deploy/ops/check_prod_accounts.sql
-- 注意：本脚本只有 SELECT，不改任何数据、不加锁、不写业务表。
-- =============================================================================

-- 0) 当前库与版本（确认连对了库，别在测试库上核出"很干净"的假结论）
SELECT DATABASE() AS db_name, VERSION() AS mysql_version, NOW() AS checked_at;

-- 1) 角色分布：ADMIN 数量应等于你预期的运维账号数（通常 1，最多 2）
SELECT role, COUNT(*) AS total,
       SUM(status = 1) AS active,
       SUM(status = 2) AS frozen,
       SUM(status = 3) AS deletion_pending,
       SUM(deleted_at IS NOT NULL) AS soft_deleted
FROM `user`
GROUP BY role;

-- 2) 全部管理员明细（逐条确认是不是你本人建的）
SELECT id, username, nickname, role, status,
       created_at, agreed_policy_at, deletion_requested_at, deleted_at
FROM `user`
WHERE role = 'ADMIN'
ORDER BY id;

-- 3) 疑似测试/工具账号：用户名命中测试夹具、压测脚本、基线采集器的命名习惯
--    （deploy/tools/*.mjs 与后端测试里的固定用户名都落在这些前缀上）
SELECT id, username, role, status, created_at
FROM `user`
WHERE deleted_at IS NULL
  AND (
       username IN ('admin', 'root', 'test', 'demo', 'guest', 'baseline_probe', 'load_m9_admin')
    OR username REGEXP '^(e2e|probe|base|base_ui|load_m[0-9]+|rl|rlr|ck|pw|m1[0-9])_'
    OR username REGEXP '^(e2e|probe|baseline|load)[_-]'
  )
ORDER BY created_at;

-- 4) 最近 30 天新建的账号（上线前不该有陌生人；有就逐个确认来源）
SELECT id, username, role, status, created_at
FROM `user`
WHERE created_at >= NOW() - INTERVAL 30 DAY
  AND deleted_at IS NULL
ORDER BY created_at DESC
LIMIT 100;

-- 5) 微信绑定情况（小程序入口是否已被人用过；预期 0 或你自己的 openid 数）
SELECT COUNT(*) AS wechat_bound_accounts
FROM `user`
WHERE wx_openid IS NOT NULL AND wx_openid <> '';

-- =============================================================================
-- 处置（人工执行，默认注释掉；确认后再逐条放开）
-- 说明：BCrypt 哈希不可逆，"这个账号现在是不是还用旧测试口令"无法从库里查出来。
--       正确做法不是拿旧口令去试登录（会触发锁定、且可能把真人也锁住），而是
--       直接冻结或改密——冻结后任何口令都登不进来。
-- -----------------------------------------------------------------------------
-- 冻结可疑账号（status=2；AuthService 对非 1 的状态一律拒绝登录）
-- UPDATE `user` SET status = 2 WHERE id = <上一步查出的 id>;
--
-- 删除多余管理员（降级比删号安全：保留数据与审计链）
-- UPDATE `user` SET role = 'USER' WHERE id = <多余的管理员 id>;
--
-- 改密走应用接口（不要手写哈希）：登录后调 PUT /api/v1/account/password，
-- 或在管理端用「用户支持」页重置；改完再复跑本脚本第 1、2 段确认。
-- =============================================================================
