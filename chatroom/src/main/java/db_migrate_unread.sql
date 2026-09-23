-- ============================================================
-- 未读消息提示 · 迁移脚本
-- 在已有的 java_chatroom 库上执行，只加一列，不动数据
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p java_chatroom < db_migrate_unread.sql
-- ============================================================

-- 1. 加"已读游标"列：记录该用户在这个会话里读到哪条消息（message_id）
--    默认 0 = 一条都没读
ALTER TABLE message_session_user
    ADD COLUMN last_read_message_id INT NOT NULL DEFAULT 0
        COMMENT '已读游标：该用户在此会话中已读到的最大 message_id，0 表示全未读';

-- 2. 可选：把「迁移前就存在的会话」标记为已读，避免老用户一打开就满屏红点。
--    想让老会话也显示未读（更适合演示效果）就把这两条注释掉。
UPDATE message_session_user msu
SET msu.last_read_message_id = COALESCE(
        (SELECT MAX(m.message_id) FROM message m WHERE m.session_id = msu.session_id), 0);

-- 3. 核对
SELECT * FROM message_session_user;
