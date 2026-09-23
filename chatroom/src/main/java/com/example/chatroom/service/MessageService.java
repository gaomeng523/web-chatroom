package com.example.chatroom.service;

import com.example.chatroom.pojo.response.MessageResponse;

import java.util.List;

public interface MessageService {

    /**
     * 查某个会话的历史消息（会校验当前用户是否属于该会话）
     */
    List<MessageResponse> getHistoryMessage(Integer userId, Integer sessionId);

    /**
     * 发消息：落库 + 更新会话时间 + 广播给会话内所有在线成员（含发送者自己）
     */
    void sendMessage(Integer fromUserId, Integer sessionId, String content);
}
