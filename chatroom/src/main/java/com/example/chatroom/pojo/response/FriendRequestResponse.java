package com.example.chatroom.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友请求的返回结构，对应用户端 client.js 里读的字段：
 * addFriendRequestUI(item.fromUserId, item.fromUserName, item.reason)
 * <p>
 * 为什么不直接用 AddFriendRequest 实体返回：实体对应 add_friend_request 表，
 * 表里没有 fromUserName 这列（名字在 user 表）。非要塞进实体的话，
 * 一旦实体上挂了 @JsonInclude(NON_NULL)，这个 join 出来的字段就会被当成
 * null 一起干掉，前端读到 undefined。表实体和接口 DTO 注定要分开。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequestResponse {
    private Integer fromUserId;
    private String fromUserName;
    private String reason;
}
