package com.example.chatroom.pojo.response;

import lombok.Data;

/**
 * 会话列表的查询结果行。
 * <p>
 * ⚠️ 群聊时一个会话会查出「成员数 - 1」行（因为 join 了"除我之外的每个成员"），
 * Java 侧用 LinkedHashMap 按 sessionId 聚合成一个 SessionListResponse。
 * 所以这里的 unreadCount / lastMessage 这些"会话级"字段每行都重复，取第一行即可。
 */
@Data
public class SessionRow {

    private Integer sessionId;

    /** 1 单聊 / 2 群聊 */
    private Integer sessionType;

    /** 群名，单聊为 null（单聊标题用 friendName） */
    private String sessionName;

    private Integer friendId;
    private String friendName;

    /** 群成员总数（含自己） */
    private Integer memberCount;

    private String lastMessage;
    private String lastMessageFrom;

    /** 最后一条消息的类型：1 文本 / 2 图片 */
    private Integer lastType;

    /**
     * 这个会话最后一条消息是否已撤回（0/1）。
     * 会话列表的最后一条如果是已撤回的消息，不能把原文显示出来，
     * 要换成"撤回了一条消息"—— 但又不能把 lastMessage 直接查成 null，
     * 因为"null"分不清是"没有消息"还是"消息被撤回了"。
     */
    private Integer lastRevoked;

    /** 当前用户在这个会话里的未读条数 */
    private Integer unreadCount;
}
