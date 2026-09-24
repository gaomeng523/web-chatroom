package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.utils.TxAfterCommit;
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
import com.example.chatroom.pojo.response.MessageSearchResponse;
import com.example.chatroom.pojo.response.UnreadCountRow;
import com.example.chatroom.pojo.response.WsMessageResponse;
import com.example.chatroom.service.FileStorageService;
import com.example.chatroom.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MessageServiceImpl implements MessageService {

    /** 与 message.content 的 varchar(2048) 对齐，超了会被 MySQL 截断/报错，所以在业务层先拦 */
    private static final int MAX_CONTENT_LENGTH = 2048;

    /** 搜索关键字长度上限，防止有人拿超长字符串去怼 like 查询 */
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final MessageMapper messageMapper;
    private final MessageSessionMapper sessionMapper;
    private final MessageSessionUserMapper sessionUserMapper;
    private final UserMapper userMapper;
    private final OnlineUserManager onlineUserManager;
    private final FileStorageService fileStorageService;

    public MessageServiceImpl(MessageMapper messageMapper,
                              MessageSessionMapper sessionMapper,
                              MessageSessionUserMapper sessionUserMapper,
                              UserMapper userMapper,
                              OnlineUserManager onlineUserManager,
                              FileStorageService fileStorageService) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.sessionUserMapper = sessionUserMapper;
        this.userMapper = userMapper;
        this.onlineUserManager = onlineUserManager;
        this.fileStorageService = fileStorageService;
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
    public void sendMessage(Integer fromUserId, Integer sessionId, String content, Integer contentType) {
        if (sessionId == null) {
            throw new UserException("会话 id 不能为空");
        }
        // 类型兜底：只有明确传 2 才算图片，其余一律按文本处理（老前端不传 contentType 也能用）
        int msgType = (contentType != null && contentType == Constant.MSG_TYPE_IMAGE)
                ? Constant.MSG_TYPE_IMAGE : Constant.MSG_TYPE_TEXT;
        String text = content == null ? "" : content.trim();
        if (msgType == Constant.MSG_TYPE_IMAGE) {
            // 图片消息的 content 必须是刚上传得到的 /upload/ 路径。
            // 不校验的话，任何人都能把任意 URL（甚至 data: / javascript: 开头的东西）
            // 塞进消息里，前端再渲染成 <img src> 就成了注入点。
            if (!text.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
                throw new UserException("图片地址不合法");
            }
        } else if (text.isEmpty()) {
            throw new UserException("消息内容不能为空");
        }
        if (text.length() > MAX_CONTENT_LENGTH) {
            throw new UserException("内容太长了，最多 " + MAX_CONTENT_LENGTH + " 个字");
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
        message.setType(msgType);
        messageMapper.insert(message);

        // 2. 会话的 last_time 顶上去，否则会话列表不会按最近聊天排序
        MessageSession session = new MessageSession();
        session.setSessionId(sessionId);
        session.setLastTime(postTime);
        sessionMapper.updateById(session);

        // 3. 广播给会话内所有在线成员 —— 一定要包含发送者自己！
        //    前端 addMessage 不做本地回显（发完就 input.value = ''），
        //    自己的消息也只能靠这条推送渲染出来，漏掉自己就会出现"只有对方看得到"。
        //
        //    注意这里用 TxAfterCommit 推迟到事务提交之后再推：
        //    在事务内推的话，万一后面回滚，对方界面上已经有了这条消息、库里却没有（幽灵消息）。
        //    另外推送是网络 IO，放在事务里会一直占着数据库连接。
        MessageResponse dto = new MessageResponse(
                message.getMessageId(), fromUserId, from.getUserName(), sessionId, text, postTime, 0, msgType);

        // 一次查出会话内所有成员的未读数（原来是在下面的循环里逐个查 = N+1）
        Map<Integer, Integer> unreadByUser = new HashMap<>();
        for (UnreadCountRow row : messageMapper.selectUnreadCounts(sessionId)) {
            unreadByUser.put(row.getUserId(), row.getUnreadCount());
        }

        TxAfterCommit.run(() -> {
            for (Integer memberId : getMemberIds(sessionId)) {
                WsMessageResponse push = WsMessageResponse.ofMessage(dto);
                if (!fromUserId.equals(memberId)) {
                    // 只有对方需要知道"我多了几条未读"。
                    // 注意这一步在 insert 之后，所以数出来的未读数已经包含刚发的这条。
                    push.setUnreadCount(unreadByUser.getOrDefault(memberId, 0));
                }
                onlineUserManager.sendTo(memberId, push);
            }
        });

        log.info("消息已发送：messageId = {}, sessionId = {}, from = {}", message.getMessageId(), sessionId, fromUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeMessage(Integer userId, Integer messageId) {
        if (messageId == null) {
            throw new UserException("消息 id 不能为空");
        }

        // 1. 先查出来做精确校验。
        //    之所以不直接跑 UPDATE 看影响行数，是因为影响行数为 0 有 4 种可能
        //    （不存在 / 不是你的 / 已撤过 / 超时），只回一句"撤回失败"用户根本不知道怎么办。
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new UserException("消息不存在");
        }
        if (!userId.equals(message.getFromId())) {
            throw new UserException("只能撤回自己发的消息");
        }
        if (message.getRevoked() != null && message.getRevoked() == 1) {
            throw new UserException("这条消息已经撤回了");
        }
        if (message.getPostTime() == null) {
            throw new UserException("消息时间异常，无法撤回");
        }
        // 时间窗口只认服务端时间。前端那个按钮的显示与否只是体验，不作数。
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(Constant.REVOKE_WINDOW_MINUTES);
        if (message.getPostTime().isBefore(deadline)) {
            throw new UserException("超过 " + Constant.REVOKE_WINDOW_MINUTES + " 分钟的消息不能撤回");
        }

        // 2. 再带条件更新一次。上面是"读"，这里是"写"，中间隔着时间窗：
        //    两个标签页同时点撤回会双双通过校验，只有这条带条件的 UPDATE
        //    能保证只有一次真正生效（返回 0 行的那次就是抢输了）。
        int updated = messageMapper.revokeMessage(messageId, userId, deadline);
        if (updated == 0) {
            throw new UserException("撤回失败，消息状态已变化，请刷新后重试");
        }

        // 3. 广播给会话内所有人（含撤回者自己）。
        //    前端不在这里本地改界面，统一等这条推送 —— 多标签页/多端才一致。
        //    同样推迟到提交后：事务回滚了就不该告诉任何人"这条消息被撤回了"。
        User from = userMapper.selectById(userId);
        WsMessageResponse push = WsMessageResponse.ofRevoke(
                messageId, message.getSessionId(), userId, from == null ? "未知用户" : from.getUserName());
        TxAfterCommit.run(() -> {
            for (Integer memberId : getMemberIds(message.getSessionId())) {
                onlineUserManager.sendTo(memberId, push);
            }
        });

        log.info("消息已撤回：messageId = {}, sessionId = {}, by = {}",
                messageId, message.getSessionId(), userId);
    }

    @Override
    public List<MessageSearchResponse> searchMessage(Integer userId, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new UserException("搜索内容不能为空");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new UserException("搜索内容太长了，最多 " + MAX_KEYWORD_LENGTH + " 个字");
        }
        // 权限隔离在 SQL 的 join 里做（见 MessageMapper.selectSearchMessage），
        // 这里不用再校验什么 —— 用户根本 join 不到不是自己成员的会话
        return messageMapper.selectSearchMessage(userId, trimmed);
    }

    @Override
    public String uploadImage(Integer userId, MultipartFile file) {
        // 复用同一个存储服务，只是换个子目录。ownerId 用 userId，文件名不会撞。
        return fileStorageService.saveImage(file, Constant.CHAT_IMAGE_DIR, userId);
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
