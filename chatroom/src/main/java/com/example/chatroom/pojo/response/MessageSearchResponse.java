package com.example.chatroom.pojo.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息搜索结果。
 * <p>
 * 比 MessageResponse 多了 sessionName / sessionType ——
 * 搜索结果跨多个会话，光有 sessionId 用户看不出这条消息是在哪个会话里说的。
 */
@Data
public class MessageSearchResponse {

    private Integer messageId;

    private Integer fromId;

    private String fromName;

    private Integer sessionId;

    /** 会话显示名：群聊是群名，单聊是对方昵称 */
    private String sessionName;

    /** 1 单聊 / 2 群聊 */
    private Integer sessionType;

    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime postTime;
}
