create database if not exists java_chatroom charset utf8;

use java_chatroom;

drop table if exists user;
create table user (
      userId int primary key auto_increment,
      username varchar(20) unique,
      -- Md5Util.encrypt() 输出 = 32位MD5 + 32位盐，共64位，必须留够长度
      password varchar(64)
);

-- 注意：password 需为 Md5Util.encrypt("123") 的结果，直接用明文会登录失败
-- insert into user values(null, 'zhangsan', '<Md5Util.encrypt("123") 的输出>');
-- insert into user values(null, 'lisi', '<Md5Util.encrypt("123") 的输出>');
