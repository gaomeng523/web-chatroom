package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.websocket.OnlineUserManager;
import com.example.chatroom.mapper.MessageMapper;
import com.example.chatroom.mapper.MessageSessionMapper;
import com.example.chatroom.mapper.MessageSessionUserMapper;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.Message;
import com.example.chatroom.pojo.dataobject.MessageSession;
import com.example.chatroom.pojo.dataobject.MessageSessionUser;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.response.MessageResponse;
import com.example.chatroom.pojo.response.WsMessageResponse;
import com.example.chatroom.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MessageServiceImpl implements MessageService {

    /** 与 message.content 的 varchar(2048) 对齐，超了会被 MySQL 截断/报错，所以在业务层先拦 */
    private static final int MAX_CONTENT_LENGTH = 2048;

    private final MessageMapper messageMapper;
    private final MessageSessionMapper sessionMapper;
    private final MessageSessionUserMapper sessionUserMapper;
    private final UserMapper userMapper;
    private final OnlineUserManager onlineUserManager;

    public MessageServiceImpl(MessageMapper messageMapper,
                              MessageSessionMapper sessionMapper,
                              MessageSessionUserMapper sessionUserMapper,
                              UserMapper userMapper,
                              OnlineUserManager onlineUserManager) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.sessionUserMapper = sessionUserMapper;
        this.userMapper = userMapper;
        this.onlineUserManager = onlineUserManager;
    }

    @Override
    public List<MessageResponse> getHistoryMessage(Integer userId, Integer sessionId) {
        if (sessionId == null) {
            throw new UserException("会话 id 不能为空");
        }
        // 会话 id 是前端传的，不校验成员身份的话改个数字就能翻别人的聊天记录
        if (!isMember(userId, sessionId)) {
            throw new UserException("你不在这个会话里");
        }
        return messageMapper.selectHistoryMessage(sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendMessage(Integer fromUserId, Integer sessionId, String content) {
        if (sessionId == null) {
            throw new UserException("会话 id 不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new UserException("消息内容不能为空");
        }
        String text = content.trim();
        if (text.length() > MAX_CONTENT_LENGTH) {
            throw new UserException("消息太长了，最多 " + MAX_CONTENT_LENGTH + " 个字");
        }
        // 关键安全校验：sessionId 由前端提供，必须确认发送者确实在该会话里，
        // 否则改一下 sessionId 就能往别人的会话里插消息
        if (!isMember(fromUserId, sessionId)) {
            throw new UserException("你不在这个会话里");
        }

        User from = userMapper.selectById(fromUserId);
        if (from == null) {
            throw new UserException("用户不存在");
        }

        // 1. 消息落库。@TableId(AUTO) 会把自增主键回填到 message 对象里
        LocalDateTime postTime = LocalDateTime.now();
        Message message = new Message();
        message.setFromId(fromUserId);
        message.setSessionId(sessionId);
        message.setContent(text);
        message.setPostTime(postTime);
        messageMapper.insert(message);

        // 2. 会话的 last_time 顶上去，否则会话列表不会按最近聊天排序
        MessageSession session = new MessageSession();
        session.setSessionId(sessionId);
        session.setLastTime(postTime);
        sessionMapper.updateById(session);

        // 3. 广播给会话内所有在线成员 —— 一定要包含发送者自己！
        //    前端 addMessage 不做本地回显（发完就 input.value = ''），
        //    自己的消息也只能靠这条推送渲染出来，漏掉自己就会出现"只有对方看得到"。
        MessageResponse dto = new MessageResponse(
                message.getMessageId(), fromUserId, from.getUserName(), sessionId, text, postTime);
        for (Integer memberId : getMemberIds(sessionId)) {
            WsMessageResponse push = WsMessageResponse.ofMessage(dto);
            if (!fromUserId.equals(memberId)) {
                // 只有对方需要知道"我多了几条未读"。
                // 注意这一步在 insert 之后，所以数出来的未读数已经包含刚发的这条。
                push.setUnreadCount(messageMapper.selectUnreadCount(memberId, sessionId));
            }
            onlineUserManager.sendTo(memberId, push);
        }

        log.info("消息已发送：messageId = {}, sessionId = {}, from = {}", message.getMessageId(), sessionId, fromUserId);
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

    /** 会话的所有成员 id */
    private List<Integer> getMemberIds(Integer sessionId) {
        LambdaQueryWrapper<MessageSessionUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageSessionUser::getSessionId, sessionId);
        List<MessageSessionUser> members = sessionUserMapper.selectList(wrapper);
        List<Integer> ids = new ArrayList<>(members.size());
        for (MessageSessionUser member : members) {
            ids.add(member.getUserId());
        }
        return ids;
    }
}
