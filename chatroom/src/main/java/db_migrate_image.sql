-- ============================================================
-- 图片消息 · 迁移脚本
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p java_chatroom < db_migrate_image.sql
-- ============================================================

-- 消息类型。图片消息仍然复用 content 列存图片路径（如 /upload/chat/9a3f.png），
-- 靠 type 决定前端渲染成文本还是 <img>。
-- 为什么不单开一列存图片路径：那样每加一种消息类型（语音、文件、位置）就要加一列，
-- 表会越来越宽；复用一个 content 列 + 一个 type 列才能无限扩展。
ALTER TABLE message
    ADD COLUMN type TINYINT NOT NULL DEFAULT 1 COMMENT '消息类型：1 文本，2 图片';

SELECT message_id, type, content FROM message ORDER BY message_id;
