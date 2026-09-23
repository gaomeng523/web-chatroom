package com.example.chatroom.pojo.response;

import com.example.chatroom.common.constant.Constant;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WebSocket 推送统一信封。
 * <p>
 * 三种推送共用一个类，靠 type 区分，前端 client.js 的 onmessage 就是按 resp.type 分发的：
 * <ul>
 *   <li>message          —— 聊天消息（sessionId / fromName / content / postTime）</li>
 *   <li>addFriendRequest —— 别人加你好友（fromUserId / fromUserName / reason）</li>
 *   <li>acceptFriend     —— 别人通过了你的好友申请（fromUserName）</li>
 * </ul>
 * 挂 @JsonInclude(NON_NULL) 把用不上的字段省掉，这里安全 —— 它是纯返回体，不是表实体。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessageResponse {

    private String type;

    // ===== type = message =====
    private Integer messageId;
    private Integer fromId;
    private String fromName;
    private Integer sessionId;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime postTime;

    /**
     * 接收者此刻的未读条数。
     * 只对"非发送者"填，发送者自己那条不带这个字段（NON_NULL 会省掉），
     * 前端用 resp.unreadCount != null 来判断要不要更新红点。
     * <p>
     * 由后端给权威值而不是让前端 +1：前端自己加会在
     * 页面刚刷新、消息撤回、多标签页同时在线等情况下算错。
     */
    private Integer unreadCount;

    // ===== type = addFriendRequest =====
    private Integer fromUserId;
    private String fromUserName;
    private String reason;

    public static WsMessageResponse ofMessage(MessageResponse message) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_MESSAGE);
        resp.setMessageId(message.getMessageId());
        resp.setFromId(message.getFromId());
        resp.setFromName(message.getFromName());
        resp.setSessionId(message.getSessionId());
        resp.setContent(message.getContent());
        resp.setPostTime(message.getPostTime());
        return resp;
    }

    public static WsMessageResponse ofAddFriendRequest(Integer fromUserId, String fromUserName, String reason) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_ADD_FRIEND_REQUEST);
        resp.setFromUserId(fromUserId);
        resp.setFromUserName(fromUserName);
        resp.setReason(reason);
        return resp;
    }

    public static WsMessageResponse ofAcceptFriend(String fromUserName) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_ACCEPT_FRIEND);
        resp.setFromUserName(fromUserName);
        return resp;
    }

    /** 借用 content 字段当错误文案，前端 resp.type === 'error' 时 alert(resp.content) */
    public static WsMessageResponse ofError(String content) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_ERROR);
        resp.setContent(content);
        return resp;
    }
}
