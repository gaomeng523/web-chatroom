package com.example.chatroom.pojo.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 前端通过 WebSocket 发来的请求体，对应 client.js：
 * websocket.send(JSON.stringify({type:'message', sessionId, content}))
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WsMessageRequest {

    /** 目前只有 "message"，后续要加别的 WS 指令就复用这个字段 */
    private String type;

    private Integer sessionId;

    private String content;

    /**
     * 消息类型：1 文本 / 2 图片。不传按文本处理（老前端兼容）。
     * type=2 时 content 必须是刚通过 POST /message/image 拿到的 /upload/... 路径。
     */
    private Integer contentType;
}
