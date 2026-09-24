package com.example.chatroom.pojo.response;

import lombok.Data;

/**
 * 批量统计未读数时的查询结果行：某个会话里、某个成员各有多少条未读。
 * <p>
 * 专门为「一次算出会话内所有成员的未读数」而生，所以是独立 DTO 而不是复用实体 ——
 * 它是个聚合结果（count + group by），表里没有对应的列。
 */
@Data
public class UnreadCountRow {

    private Integer userId;

    /** 该成员的未读条数。没有未读时是 0，不是 null */
    private Integer unreadCount;
}
