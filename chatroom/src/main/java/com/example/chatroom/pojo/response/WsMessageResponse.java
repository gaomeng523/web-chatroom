package com.example.chatroom.pojo.response;

import com.example.chatroom.common.constant.Constant;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WebSocket 推送统一信封。
 * <p>
 * 各种推送共用一个类，靠 type 区分，前端 client.js 的 onmessage 就是按 resp.type 分发的：
 * <ul>
 *   <li>message          —— 聊天消息（sessionId / fromName / content / contentType / postTime / unreadCount）</li>
 *   <li>addFriendRequest —— 别人加你好友（fromUserId / fromUserName / reason）</li>
 *   <li>acceptFriend     —— 别人通过了你的好友申请（fromUserName）</li>
 *   <li>revoke           —— 某条消息被撤回（messageId / sessionId / fromName）</li>
 *   <li>groupCreated     —— 你被拉进了一个群（groupName）</li>
 *   <li>pong             —— 心跳应答，只有 type 一个字段，前端直接忽略</li>
 *   <li>error            —— 你发来的 WS 请求没处理成功（content 当错误文案）</li>
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

    /**
     * 消息类型：1 文本 / 2 图片。字段名特意跟前端发上来的 WsMessageRequest.contentType 对齐。
     * <p>
     * ⚠️ 不能省。省了的话接收方只拿到 content = "/upload/chat/xxx.png"，
     * 前端没有任何依据判断它是图片，会把这串路径当文本直接显示出来。
     */
    private Integer contentType;

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

    // ===== type = groupCreated =====
    /** 群名称。注意它跟 sessionId 不一样：被拉进群的人手里还没有 sessionId，得自己刷一次列表才知道 */
    private String groupName;

    public static WsMessageResponse ofMessage(MessageResponse message) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_MESSAGE);
        resp.setMessageId(message.getMessageId());
        resp.setFromId(message.getFromId());
        resp.setFromName(message.getFromName());
        resp.setSessionId(message.getSessionId());
        resp.setContent(message.getContent());
        resp.setPostTime(message.getPostTime());
        resp.setContentType(message.getType());
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

    /**
     * 撤回通知。前端靠 messageId 找到界面上那条消息，换成"XX 撤回了一条消息"。
     * 撤回同样要广播给会话里所有人（含撤回者自己）——
     * 前端不自己改界面，一律等这条推送，这样多标签页、多端才一致。
     * <p>
     * 带 fromId 是为了让前端判断"是不是我撤的"。光比 fromName 在群聊里不够稳：
     * 用户名虽然有唯一约束，但前端拿到的 selfUsername 是页面初始化时的快照。
     */
    public static WsMessageResponse ofRevoke(Integer messageId, Integer sessionId,
                                             Integer fromId, String fromName) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_REVOKE);
        resp.setMessageId(messageId);
        resp.setSessionId(sessionId);
        resp.setFromId(fromId);
        resp.setFromName(fromName);
        return resp;
    }

    /**
     * 你被拉进了一个群（新建群拉人 / 往已有群加人，两种情况共用这一条推送）。
     * <p>
     * 不推 sessionId：被拉的人此刻本地还没有这个会话，前端收到后统一重新拉一次
     * /sessionList，比在推送里塞一堆会话信息再让前端手工拼装可靠得多
     * （拼接要处理 unreadCount、成员列表、lastMessage 一堆字段，很容易拼错）。
     */
    public static WsMessageResponse ofGroupCreated(String groupName) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_GROUP_CREATED);
        resp.setGroupName(groupName);
        return resp;
    }

    /**
     * 心跳应答。只有 type 一个字段（NON_NULL 把其余全省了），前端收到就 return。
     * <p>
     * 它存在的唯一意义是让客户端有个"服务端还活着"的明确信号 ——
     * 客户端据此判断半开连接并主动断开重连，见 client.js 的 startHeartbeat()。
     */
    public static WsMessageResponse ofPong() {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_PONG);
        return resp;
    }
}
