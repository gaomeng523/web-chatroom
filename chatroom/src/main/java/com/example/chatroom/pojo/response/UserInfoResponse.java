package com.example.chatroom.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息响应。
 * <p>
 * 字段名必须叫 username（小写），前端 client.js 读的是 body.username；
 * 而实体 User 的字段是 userName，Jackson 序列化出来是 "userName"，两者对不上。
 * <p>
 * 单独定义一个 DTO 而不是直接返回 User 实体，有两个原因：
 * 1. 字段名可以和前端约定对齐，不受实体命名影响；
 * 2. 不会把 password 一起返回给前端。
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class UserInfoResponse {
    private Integer userId;
    private String username;
}
