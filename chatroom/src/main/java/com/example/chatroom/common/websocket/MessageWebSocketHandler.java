package com.example.chatroom.common.websocket;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.pojo.request.WsMessageRequest;
import com.example.chatroom.pojo.response.WsMessageResponse;
import com.example.chatroom.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 聊天 WebSocket 端点。
 * <p>
 * 用 Spring 原生的 TextWebSocketHandler 而不是 JSR-356 的 @ServerEndpoint：
 * 后者的实例是 Tomcat 创建的，不归 Spring 管，@Autowired 注进来的全是 null，
 * 得靠静态字段或 ApplicationContextAware 绕一圈，属于自找麻烦。
 */
@Slf4j
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

    private final MessageService messageService;
    private final OnlineUserManager onlineUserManager;
    private final ObjectMapper objectMapper;

    public MessageWebSocketHandler(MessageService messageService,
                                   OnlineUserManager onlineUserManager,
                                   ObjectMapper objectMapper) {
        this.messageService = messageService;
        this.onlineUserManager = onlineUserManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Integer userId = currentUserId(session);
        if (userId == null) {
            // 正常情况下 HandshakeInterceptor 已经拦掉了，这里只是兜底
            log.warn("WebSocket 连接缺少 userId，直接关闭");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        onlineUserManager.add(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Integer userId = currentUserId(session);
        if (userId == null) {
            return;
        }
        // 收到任何一帧就刷新活跃时间。这是空闲回收唯一的"活着"信号来源，
        // 所以必须放在最前面 —— 放在校验之后的话，心跳以外的非法请求就白发了，
        // 而客户端恰好只发那两种（心跳 + 消息）。
        onlineUserManager.touch(session);
        try {
            WsMessageRequest request = objectMapper.readValue(message.getPayload(), WsMessageRequest.class);

            // 心跳：客户端每 25s 发一次。只回一个 pong 就返回，不进业务层、不落库、不广播。
            // 不打日志 —— 一个在线用户每分钟两条，打了会把日志冲得没法看。
            if (Constant.WS_TYPE_PING.equals(request.getType())) {
                onlineUserManager.sendTo(userId, WsMessageResponse.ofPong());
                return;
            }

            if (!Constant.WS_TYPE_MESSAGE.equals(request.getType())) {
                log.warn("忽略未知的 WebSocket 指令：{}", request.getType());
                return;
            }
            messageService.sendMessage(userId, request.getSessionId(),
                    request.getContent(), request.getContentType());
        } catch (UserException e) {
            // 业务校验失败（不是会话成员、内容为空……）只回给发送者，连接不能断
            log.warn("WebSocket 消息处理失败：userId = {}, 原因 = {}", userId, e.getMessage());
            onlineUserManager.sendTo(userId, WsMessageResponse.ofError(e.getMessage()));
        } catch (Exception e) {
            log.error("WebSocket 消息处理异常：userId = " + userId, e);
            onlineUserManager.sendTo(userId, WsMessageResponse.ofError("消息发送失败，请稍后重试"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Integer userId = currentUserId(session);
        if (userId != null) {
            onlineUserManager.remove(userId, session);
        }
        log.info("WebSocket 已断开：userId = {}, status = {}", userId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常：{}", exception.getMessage());
    }

    /** HandshakeInterceptor 在握手里塞进 attributes 的当前用户 id */
    private Integer currentUserId(WebSocketSession session) {
        return (Integer) session.getAttributes().get(Constant.CURRENT_USER_ID);
    }
}
