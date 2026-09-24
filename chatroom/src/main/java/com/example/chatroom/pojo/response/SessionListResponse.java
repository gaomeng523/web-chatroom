package com.example.chatroom.pojo.response;

import com.example.chatroom.pojo.dataobject.Friend;
import lombok.Data;

import java.util.List;

@Data
public class SessionListResponse {

    private Integer sessionId;

    /** 1 单聊 / 2 群聊（Constant.SESSION_TYPE_*） */
    private Integer sessionType;

    /** 群名。单聊为 null，前端用自己的 friends[0].friendName 当标题 */
    private String sessionName;

    /** 群成员数（含自己）；单聊恒为 2 */
    private Integer memberCount;

    private String lastMessage;

    /** 最后一条消息是谁发的，群聊时前端显示成 "张三: 内容" 用 */
    private String lastMessageFrom;

    /** 群成员列表（单聊就是对方一个人）；群聊时前端用来展示成员 */
    private List<Friend> friends;

    /** 未读条数，>0 时前端在会话项右侧显示红点 */
    private Integer unreadCount;
}
