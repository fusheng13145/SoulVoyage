-- B-09 乐观锁：可变聚合新增版本列（只增列，不改既有列语义）
-- 影响表：mood_check_in / plan_item / report / user_preferences
ALTER TABLE mood_check_in ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plan_item ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE report ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_preferences ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
