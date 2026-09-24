-- ============================================================
-- 头像 · 迁移脚本
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p java_chatroom < db_migrate_avatar.sql
-- ============================================================

-- 存头像图片的相对路径，例如 /upload/avatar/4_9f2c.png
-- 不存 base64：base64 会让每行数据膨胀 33%，每次查 user 都要把图片拖出来，
-- 而且没法利用浏览器的图片缓存。
ALTER TABLE user
    ADD COLUMN avatar VARCHAR(255) DEFAULT NULL COMMENT '头像图片相对路径，NULL 表示用默认首字母头像';

SELECT user_id, user_name, avatar FROM user;
