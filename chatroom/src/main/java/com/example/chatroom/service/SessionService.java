package com.example.chatroom.service;

import com.example.chatroom.pojo.response.SessionCreateResponse;
import com.example.chatroom.pojo.response.SessionListResponse;

import java.util.List;

public interface SessionService {

    /**
     * 查当前用户的会话列表
     */
    List<SessionListResponse> getSessionList(Integer userId);

    /**
     * 创建或复用与某人的会话，返回 sessionId
     */
    SessionCreateResponse createSession(Integer selfUserId, Integer toUserId);

    /**
     * 把某人某会话的未读清空（已读游标推到当前最大 message_id）
     */
    void markSessionRead(Integer userId, Integer sessionId);
}
