package com.example.chatroom.service;

import com.example.chatroom.pojo.response.MessageResponse;
import com.example.chatroom.pojo.response.MessageSearchResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MessageService {

    /**
     * 查某个会话的历史消息（会校验当前用户是否属于该会话）
     */
    List<MessageResponse> getHistoryMessage(Integer userId, Integer sessionId);

    /**
     * 发消息：落库 + 更新会话时间 + 广播给会话内所有在线成员（含发送者自己）
     *
     * @param contentType 消息类型，null 或 1 按文本处理，2 是图片（此时 content 必须是 /upload/ 下的路径）
     */
    void sendMessage(Integer fromUserId, Integer sessionId, String content, Integer contentType);

    /**
     * 撤回消息：只能撤自己发的、且不超过 REVOKE_WINDOW_MINUTES 分钟，
     * 成功后广播给会话内所有人（含自己）
     */
    void revokeMessage(Integer userId, Integer messageId);

    /**
     * 搜索自己所有会话里的文本消息（只搜自己是成员的会话，排除已撤回）
     */
    List<MessageSearchResponse> searchMessage(Integer userId, String keyword);

    /**
     * 上传一张聊天图片，返回可访问的路径。
     * 注意这里只负责"存文件"，不负责发消息 ——
     * 前端拿到路径后再通过 WebSocket 发一条 contentType=2 的消息。
     */
    String uploadImage(Integer userId, MultipartFile file);
}
