package com.example.chatroom.service;

import com.example.chatroom.pojo.response.SessionCreateResponse;
import com.example.chatroom.pojo.response.SessionListResponse;

import java.util.List;

public interface SessionService {
    /**
     * 查当前用户的会话列表（单聊 + 群聊混排，按最近活跃倒序）
     */
    List<SessionListResponse> getSessionList(Integer userId);

    /**
     * 创建或复用与某人的单聊会话，返回 sessionId
     */
    SessionCreateResponse createSession(Integer selfUserId, Integer toUserId);

    /**
     * 把某人某会话的未读清空（已读游标推到当前最大 message_id）
     */
    void markSessionRead(Integer userId, Integer sessionId);

    /**
     * 建群：创建者自己是成员之一，memberIds 只放"别人"。
     *
     * @param creatorId 创建者（自动入群，不用放进 memberIds）
     * @param name      群名称
     * @param memberIds 被拉进来的好友 id，可以重复、可以混进创建者自己（内部会去重去自己）
     * @return 新会话的 sessionId
     */
    SessionCreateResponse createGroup(Integer creatorId, String name, List<Integer> memberIds);

    /**
     * 往已有群里加人。所有群成员都有加人权限（不做群主概念，课件没要求）。
     */
    void addGroupMember(Integer operatorId, Integer sessionId, Integer newMemberId);

    /**
     * 退群。最后一个人退群时连会话一起删掉。
     */
    void quitGroup(Integer userId, Integer sessionId);
}
