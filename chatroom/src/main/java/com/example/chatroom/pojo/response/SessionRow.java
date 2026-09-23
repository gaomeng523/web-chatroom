package com.example.chatroom.pojo.response;

import lombok.Data;

@Data
public class SessionRow {

    private Integer sessionId;
    private Integer friendId;
    private String friendName;
    private String lastMessage;

    /** 当前用户在这个会话里的未读条数 */
    private Integer unreadCount;
}
