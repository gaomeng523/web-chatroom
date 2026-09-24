package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.utils.TxAfterCommit;
import com.example.chatroom.common.websocket.OnlineUserManager;
import com.example.chatroom.mapper.FriendMapper;
import com.example.chatroom.mapper.MessageMapper;
import com.example.chatroom.mapper.MessageSessionMapper;
import com.example.chatroom.mapper.MessageSessionUserMapper;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.dataobject.FriendRelation;
import com.example.chatroom.pojo.dataobject.MessageSession;
import com.example.chatroom.pojo.dataobject.MessageSessionUser;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.response.SessionCreateResponse;
import com.example.chatroom.pojo.response.SessionListResponse;
import com.example.chatroom.pojo.response.SessionRow;
import com.example.chatroom.pojo.response.WsMessageResponse;
import com.example.chatroom.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SessionServiceImpl implements SessionService {

    /** 会话列表里，最后一条消息已撤回时显示的占位文本（前端 client.js 里有一份一样的） */
    private static final String REVOKED_PLACEHOLDER = "撤回了一条消息";

    /** 最后一条是图片时的占位文本 */
    private static final String IMAGE_PLACEHOLDER = "[图片]";

    private static final int MAX_GROUP_NAME_LENGTH = 64;

    private final MessageSessionMapper sessionMapper;
    private final MessageSessionUserMapper sessionUserMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final FriendMapper friendMapper;
    private final OnlineUserManager onlineUserManager;

    public SessionServiceImpl(MessageSessionMapper sessionMapper,
                              MessageSessionUserMapper sessionUserMapper,
                              MessageMapper messageMapper,
                              UserMapper userMapper,
                              FriendMapper friendMapper,
                              OnlineUserManager onlineUserManager) {
        this.sessionMapper = sessionMapper;
        this.sessionUserMapper = sessionUserMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.friendMapper = friendMapper;
        this.onlineUserManager = onlineUserManager;
    }

    @Override
    public List<SessionListResponse> getSessionList(Integer userId) {
        List<SessionRow> rows = sessionMapper.selectSessionList(userId);

        // 必须用 LinkedHashMap：SQL 已经按 last_time 倒序排好了，
        // HashMap 会打乱顺序，会话列表就不按最近聊的排了。
        // 顺带它也承担了"群聊多行合并成一行"的职责。
        Map<Integer, SessionListResponse> grouped = new LinkedHashMap<>();

        for (SessionRow row : rows) {
            // 同一个 sessionId 第一次遇到就建个空壳，之后再遇到直接取出来
            SessionListResponse item = grouped.computeIfAbsent(row.getSessionId(), id -> {
                SessionListResponse r = new SessionListResponse();
                r.setSessionId(id);
                r.setSessionType(row.getSessionType());
                r.setSessionName(row.getSessionName());
                r.setMemberCount(row.getMemberCount());
                r.setLastMessageFrom(row.getLastMessageFrom());
                r.setUnreadCount(row.getUnreadCount());
                r.setLastMessage(summarize(row));
                r.setFriends(new ArrayList<>());
                return r;
            });
            // 成员表里排掉自己，剩下的都是"对方"：单聊是 1 个人，群聊是 N 个人。
            // friendId 为 null 说明这个群只剩我一个人了（SQL 那边是 left join），
            // 不能往 friends 里塞一个 id 为 null 的空对象，前端会渲染出一个空白项。
            if (row.getFriendId() != null) {
                item.getFriends().add(new Friend(row.getFriendId(), row.getFriendName()));
            }
        }

        return new ArrayList<>(grouped.values());
    }

    /**
     * 把最后一条消息整理成列表里能直接显示的一行字。
     * 优先级：已撤回 &gt; 图片 &gt; 原文。
     */
    private static String summarize(SessionRow row) {
        if (row.getLastRevoked() != null && row.getLastRevoked() == 1) {
            return REVOKED_PLACEHOLDER;
        }
        if (row.getLastType() != null && row.getLastType() == Constant.MSG_TYPE_IMAGE) {
            return IMAGE_PLACEHOLDER;
        }
        return row.getLastMessage();
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

        // 3. 🔴 必须已是好友。
        // 不校验的后果：POST /session?toUserId=<任意ID> 就能和陌生人建出会话，
        // 而 sendMessage 只校验"我在这个会话里"、不校验好友关系，
        // 于是"建会话 + 发消息"两步就能给任意用户发骚扰消息，完全绕过好友申请流程。
        // 这里和 createGroup 的 isFriend 校验保持同一口径 —— 好友关系是聊天功能的门票。
        if (!isFriend(selfUserId, toUserId)) {
            throw new UserException("对方不是你的好友，请先添加好友");
        }

        // 4. 已经有共同会话就直接复用，不重复建
        Integer existSessionId = sessionMapper.findCommonSession(selfUserId, toUserId);
        if (existSessionId != null) {
            log.info("复用已有会话：sessionId = {}", existSessionId);
            return new SessionCreateResponse(existSessionId);
        }

        // 5. 新建：1 行会话 + 2 行成员，3 次写必须同一事务，
        //    否则中途失败会留下"有会话但没成员"的脏数据。
        MessageSession session = new MessageSession();
        session.setType(Constant.SESSION_TYPE_SINGLE);
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

    // ==================== 群聊 ====================
    // 注意整个群聊只用了 message_session 的 type / name 两个新字段，
    // 成员关系完全复用 message_session_user —— 它本来就是 (session_id, user_id) 多对多，
    // 单聊 2 行、群聊 N 行，这就是课件说的"后端已为群聊预留了扩展"。

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionCreateResponse createGroup(Integer creatorId, String name, List<Integer> memberIds) {
        if (!StringUtils.hasText(name)) {
            throw new UserException("群名称不能为空");
        }
        String groupName = name.trim();
        if (groupName.length() > MAX_GROUP_NAME_LENGTH) {
            throw new UserException("群名称最多 " + MAX_GROUP_NAME_LENGTH + " 个字");
        }

        // 去重 + 去掉自己（LinkedHashSet 既去重又保持前端选的顺序）
        Set<Integer> members = new LinkedHashSet<>();
        if (memberIds != null) {
            for (Integer id : memberIds) {
                if (id != null && !id.equals(creatorId)) {
                    members.add(id);
                }
            }
        }
        if (members.isEmpty()) {
            throw new UserException("至少要选择一位好友");
        }

        // 关键校验：只能拉自己的好友进群。
        // 不校验的话，前端随便传几个 userId 就能把陌生人拉进群，
        // 进而让他们看到群里的全部聊天记录。
        for (Integer id : members) {
            if (!isFriend(creatorId, id)) {
                throw new UserException("只能拉自己的好友进群");
            }
        }

        // 1 行会话 + (1 + N) 行成员，必须同一事务
        MessageSession session = new MessageSession();
        session.setType(Constant.SESSION_TYPE_GROUP);
        session.setName(groupName);
        session.setLastTime(LocalDateTime.now());
        sessionMapper.insert(session);

        Integer sessionId = session.getSessionId();
        sessionUserMapper.insert(new MessageSessionUser(sessionId, creatorId, 0));
        for (Integer id : members) {
            sessionUserMapper.insert(new MessageSessionUser(sessionId, id, 0));
        }

        // 通知被拉进来的成员：他们多半正开着网页，推一下就能立刻看到这个群。
        // 推迟到事务提交后推 —— 群还没建成就告诉人家"你被拉进群了"，
        // 对方一刷新列表发现没有这个群，会以为程序坏了。
        WsMessageResponse push = WsMessageResponse.ofGroupCreated(groupName);
        TxAfterCommit.run(() -> {
            for (Integer id : members) {
                onlineUserManager.sendTo(id, push);
            }
        });

        log.info("新建群聊：sessionId = {}, name = {}, 创建者 = {}, 成员 = {}",
                sessionId, groupName, creatorId, members);
        return new SessionCreateResponse(sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addGroupMember(Integer operatorId, Integer sessionId, Integer newMemberId) {
        if (sessionId == null || newMemberId == null) {
            throw new UserException("参数异常");
        }
        MessageSession session = requireGroup(sessionId);
        if (!isMember(operatorId, sessionId)) {
            throw new UserException("你不在这个群里");
        }
        if (isMember(newMemberId, sessionId)) {
            throw new UserException("对方已经在群里了");
        }
        if (!isFriend(operatorId, newMemberId)) {
            throw new UserException("只能拉自己的好友进群");
        }

        sessionUserMapper.insert(new MessageSessionUser(sessionId, newMemberId, 0));
        // 同样等提交后再推：加人失败回滚了却告诉对方"你进群了"，对方会找不到这个群
        WsMessageResponse push = WsMessageResponse.ofGroupCreated(session.getName());
        TxAfterCommit.run(() -> onlineUserManager.sendTo(newMemberId, push));
        log.info("群成员已添加：sessionId = {}, 新成员 = {}, 操作者 = {}",
                sessionId, newMemberId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void quitGroup(Integer userId, Integer sessionId) {
        if (sessionId == null) {
            throw new UserException("会话 id 不能为空");
        }
        requireGroup(sessionId);
        if (!isMember(userId, sessionId)) {
            throw new UserException("你不在这个群里");
        }

        LambdaQueryWrapper<MessageSessionUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageSessionUser::getSessionId, sessionId)
                .eq(MessageSessionUser::getUserId, userId);
        sessionUserMapper.delete(wrapper);

        // 最后一个人退群就把会话本身也删了，免得留下谁也访问不到的空壳。
        // 消息记录不删（留在 message 表里），真要清理是另一个运维动作。
        long left = countMembers(sessionId);
        if (left == 0) {
            sessionMapper.deleteById(sessionId);
            log.info("群已空，删除会话：sessionId = {}", sessionId);
        }
        log.info("已退群：userId = {}, sessionId = {}, 剩余成员 = {}", userId, sessionId, left);
    }

    /** 取出会话并断言它是个群聊，顺便省掉每个调用方重复判空 */
    private MessageSession requireGroup(Integer sessionId) {
        MessageSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new UserException("会话不存在");
        }
        if (session.getType() == null || session.getType() != Constant.SESSION_TYPE_GROUP) {
            throw new UserException("这不是群聊");
        }
        return session;
    }

    /** 两人之间是否存在好友关系 */
    private boolean isFriend(Integer userId, Integer friendId) {
        if (userId == null || friendId == null) {
            return false;
        }
        LambdaQueryWrapper<FriendRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRelation::getUserId, userId)
                .eq(FriendRelation::getFriendId, friendId);
        return friendMapper.selectCount(wrapper) > 0;
    }

    private long countMembers(Integer sessionId) {
        LambdaQueryWrapper<MessageSessionUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MessageSessionUser::getSessionId, sessionId);
        return sessionUserMapper.selectCount(wrapper);
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
