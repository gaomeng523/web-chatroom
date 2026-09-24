-- ============================================================
-- 消息撤回 · 迁移脚本
-- 在已有的 java_chatroom 库上执行，只加一列，不动数据
-- 执行：mysql -h 127.0.0.1 -P 3306 -u root -p java_chatroom < db_migrate_revoke.sql
-- ============================================================

-- 加"已撤回"标记。
-- 为什么用软删除（加标记）而不是物理删除 delete：
--   1. 撤回要在双方界面上留下"XX 撤回了一条消息"的痕迹，行删了就没地方放这个信息；
--   2. 撤回要能被拒（超 2 分钟不能撤），行删了就查不到原发布时间，没法判断。
ALTER TABLE message
    ADD COLUMN revoked TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否已撤回：0 正常，1 已撤回';

-- 核对
SELECT message_id, from_id, session_id, revoked, post_time FROM message ORDER BY message_id;
