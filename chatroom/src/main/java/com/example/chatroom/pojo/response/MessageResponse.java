package com.example.chatroom.pojo.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息返回体（历史消息查询 + WebSocket 广播都用它）。
 * <p>
 * 和表实体 Message 分开的原因：fromName 是 join user 表出来的，表里没这一列。
 * 如果把 @JsonInclude(NON_NULL) 挂在表实体上，这个字段会被一起干掉，前端读到 undefined。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    private Integer messageId;

    private Integer fromId;

    /** join user 表得到，前端靠它判断消息是不是自己发的 */
    private String fromName;

    private Integer sessionId;

    private String content;

    /**
     * 前端 formatTime() 按「空格」切分取 HH:mm，
     * Jackson 对 LocalDateTime 默认输出带 T 的 ISO 格式，必须指定 pattern。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime postTime;

    /**
     * 是否已撤回（0 正常 / 1 已撤回）。
     * 已撤回的消息后端会把 content 置成 null —— 原文留在库里做审计，
     * 但不该再发给客户端，前端只负责渲染成"XX 撤回了一条消息"。
     */
    private Integer revoked;

    /**
     * 消息类型：1 文本 / 2 图片（取值见 Constant.MSG_TYPE_*）。
     * type=2 时 content 里放的是图片路径，前端要渲染成 &lt;img&gt; 而不是文本。
     */
    private Integer type;
}
