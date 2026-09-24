-- ============================================================
-- 群聊 · 迁移脚本
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p java_chatroom < db_migrate_group.sql
-- ============================================================

-- 会话类型：1 单聊，2 群聊
ALTER TABLE message_session
    ADD COLUMN type TINYINT NOT NULL DEFAULT 1 COMMENT '会话类型：1 单聊，2 群聊';

-- 群名称。单聊为 NULL —— 单聊的标题是"对方的昵称"，那是查出来的、不是存出来的。
-- 已经有 type 能区分了，不需要再给单聊存一个冗余的名字。
ALTER TABLE message_session
    ADD COLUMN name VARCHAR(64) DEFAULT NULL COMMENT '群名称，单聊为 NULL';

-- 注意：这里**不需要**建任何"群成员表"。
-- message_session_user 本来就是 (session_id, user_id) 的多对多结构，
-- 单聊是 2 行、群聊是 N 行 —— 课件说的"后端已为群聊预留扩展"指的就是这个。
-- 所以群聊只加了两个描述性字段，成员关系一行代码都不用改。

SELECT session_id, type, name, last_time FROM message_session;
SELECT session_id, user_id, last_read_message_id FROM message_session_user;
