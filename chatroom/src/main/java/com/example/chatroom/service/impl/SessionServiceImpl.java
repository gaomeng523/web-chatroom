package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.mapper.MessageMapper;
import com.example.chatroom.mapper.MessageSessionMapper;
import com.example.chatroom.mapper.MessageSessionUserMapper;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.dataobject.MessageSession;
import com.example.chatroom.pojo.dataobject.MessageSessionUser;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.response.SessionCreateResponse;
import com.example.chatroom.pojo.response.SessionListResponse;
import com.example.chatroom.pojo.response.SessionRow;
import com.example.chatroom.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SessionServiceImpl implements SessionService {

    private final MessageSessionMapper sessionMapper;
    private final MessageSessionUserMapper sessionUserMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;

    public SessionServiceImpl(MessageSessionMapper sessionMapper,
                              MessageSessionUserMapper sessionUserMapper,
                              MessageMapper messageMapper,
                              UserMapper userMapper) {
        this.sessionMapper = sessionMapper;
        this.sessionUserMapper = sessionUserMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
    }

    @Override
    public List<SessionListResponse> getSessionList(Integer userId) {
        List<SessionRow> rows = sessionMapper.selectSessionList(userId);

        // 必须用 LinkedHashMap：SQL 已经按 last_time 倒序排好了，
        // HashMap 会打乱顺序，会话列表就不按最近聊的排了。
        Map<Integer, SessionListResponse> grouped = new LinkedHashMap<>();

        for (SessionRow row : rows) {
            // 同一个 sessionId 第一次遇到就建个空壳，之后再遇到直接取出来
            SessionListResponse item = grouped.computeIfAbsent(row.getSessionId(), id -> {
                SessionListResponse r = new SessionListResponse();
                r.setSessionId(id);
                r.setLastMessage(row.getLastMessage());
                r.setUnreadCount(row.getUnreadCount());
                r.setFriends(new ArrayList<>());
                return r;
            });
            // 成员表里排掉自己，剩下的就是对方
            item.getFriends().add(new Friend(row.getFriendId(), row.getFriendName()));
        }

        return new ArrayList<>(grouped.values());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionCreateResponse createSession(Integer selfUserId, Integer toUserId) {
        // 1. 参数校验
        if (toUserId == null) {
            throw new UserException("参数异常");
        }
        if (selfUserId.equals(toUserId)) {
            throw new UserException("不能和自己聊天");
        }

        // 2. 对方必须存在
        User target = userMapper.selectById(toUserId);
        if (target == null) {
            throw new UserException("用户不存在");
        }

        // 3. 已经有共同会话就直接复用，不重复建
        Integer existSessionId = sessionMapper.findCommonSession(selfUserId, toUserId);
        if (existSessionId != null) {
            log.info("复用已有会话：sessionId = {}", existSessionId);
            return new SessionCreateResponse(existSessionId);
        }

        // 4. 新建：1 行会话 + 2 行成员，3 次写必须同一事务，
        //    否则中途失败会留下"有会话但没成员"的脏数据。
        MessageSession session = new MessageSession();
        session.setLastTime(LocalDateTime.now());
        sessionMapper.insert(session);

        // MP 靠 @TableId(AUTO) 把自增主键回填到 session 对象里
        Integer sessionId = session.getSessionId();
        // 第三个参数是已读游标：新会话一条消息都没有，从 0 开始
        sessionUserMapper.insert(new MessageSessionUser(sessionId, selfUserId, 0));
        sessionUserMapper.insert(new MessageSessionUser(sessionId, toUserId, 0));

        log.info("新建会话：sessionId = {}, {} <-> {}", sessionId, selfUserId, toUserId);
        return new SessionCreateResponse(sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSessionRead(Integer userId, Integer sessionId) {
        if (sessionId == null) {
            throw new UserException("会话 id 不能为空");
        }
        // sessionId 来自前端，不校验成员身份的话改个数字就能动别人的已读状态
        if (!isMember(userId, sessionId)) {
            throw new UserException("你不在这个会话里");
        }

        // 直接推到会话里当前最大的 message_id，等于"这个会话全部已读"。
        // 让后端自己查 max，而不是让前端传 message_id：
        // 前端可能拿到的是旧列表，传上来的 id 反而不准。
        Integer maxMessageId = messageMapper.selectMaxMessageId(sessionId);
        int updated = sessionUserMapper.markRead(userId, sessionId, maxMessageId);
        log.info("标记已读：userId = {}, sessionId = {}, 游标 -> {}, 影响行数 = {}",
                userId, sessionId, maxMessageId, updated);
    }

    /** 当前用户是否为该会话的成员 */
    private boolean isMember(Integer userId, Integer sessionId) {
        if (userId == null) {
            return false;
        }
        LambdaQueryWrapper<MessageSessionUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageSessionUser::getSessionId, sessionId)
                .eq(MessageSessionUser::getUserId, userId);
        return sessionUserMapper.selectCount(wrapper) > 0;
    }
}
