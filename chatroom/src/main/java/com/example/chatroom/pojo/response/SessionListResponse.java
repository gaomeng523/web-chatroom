package com.example.chatroom.pojo.response;

import com.example.chatroom.pojo.dataobject.Friend;
import lombok.Data;

import java.util.List;

@Data
public class SessionListResponse {
    private Integer sessionId;
    private String lastMessage;
    private List<Friend> friends;

    /** 未读条数，>0 时前端在会话项右侧显示红点 */
    private Integer unreadCount;
}
