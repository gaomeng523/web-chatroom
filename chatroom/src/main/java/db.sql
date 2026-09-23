-- ============================================================
-- 网页版聊天室 - 完整建库脚本（全新安装用）
--
-- ⚠️ 这个脚本会 DROP 已有表，把数据全部清掉！
--    已经跑过、库里有数据的，不要执行这个，改用迁移脚本。
--
-- 列名必须全部蛇形。原因：MyBatis-Plus 生成 SQL 时会把实体驼峰字段名
-- 转成蛇形列名（GlobalConfig.DbConfig.tableUnderline 默认 true），
-- 所以库里列名写成驼峰的话，MP 生成的 session_id 会和真实列 sessionId
-- 对不上，报 Unknown column。
-- ============================================================

create database if not exists java_chatroom charset utf8mb4;

use java_chatroom;

-- ============ 用户表 ============
drop table if exists user;
create table user (
    user_id   int primary key auto_increment,
    user_name varchar(20) unique,
    -- Md5Util.encrypt() 输出 = 32位MD5 + 32位盐，共64位，必须留够长度
    password  varchar(64)
) engine = InnoDB default charset = utf8mb4 comment = '用户表';

-- ============ 好友关系表 ============
-- 双向存储：A 加 B 成功后，会同时插入 (A,B) 和 (B,A) 两条记录
drop table if exists friend;
create table friend (
    user_id   int not null comment '用户 id',
    friend_id int not null comment '好友的 user_id',
    primary key (user_id, friend_id)
) engine = InnoDB default charset = utf8mb4 comment = '好友关系表';

-- ============ 添加好友请求表 ============
drop table if exists add_friend_request;
create table add_friend_request (
    from_user_id int          not null comment '请求是谁发的',
    to_user_id   int          not null comment '请求要发给谁',
    reason       varchar(100) not null default '' comment '添加好友的理由',
    primary key (from_user_id, to_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '添加好友请求表';

-- ============ 会话表 ============
drop table if exists message_session;
create table message_session (
    session_id int primary key auto_increment,
    last_time  datetime default null comment '最后一条消息的时间，用于会话列表排序'
) engine = InnoDB default charset = utf8mb4 comment = '会话表';

-- ============ 会话成员表 ============
-- 1 对 1 会话这里会有 2 行：(session_id, 我) 和 (session_id, 对方)
-- 联合主键顺带防止同一个人被重复加进同一个会话
drop table if exists message_session_user;
create table message_session_user (
    session_id            int not null,
    user_id               int not null,
    last_read_message_id  int not null default 0 comment '已读游标：该用户在此会话中已读到的最大 message_id，0 表示全未读',
    primary key (session_id, user_id),
    key idx_msu_user (user_id)
) engine = InnoDB default charset = utf8mb4 comment = '会话成员表';

-- ============ 消息表 ============
drop table if exists message;
create table message (
    message_id int primary key auto_increment,
    from_id    int           default null comment '发送者 user_id',
    session_id int           default null comment '所属会话',
    content    varchar(2048) default null comment '消息内容',
    post_time  datetime      default null comment '发送时间',
    -- 按会话查历史消息、取最后一条，都走这个联合索引
    key idx_msg_session_time (session_id, post_time)
) engine = InnoDB default charset = utf8mb4 comment = '消息表';
