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
        try {
            WsMessageRequest request = objectMapper.readValue(message.getPayload(), WsMessageRequest.class);
            if (!Constant.WS_TYPE_MESSAGE.equals(request.getType())) {
                log.warn("忽略未知的 WebSocket 指令：{}", request.getType());
                return;
            }
            messageService.sendMessage(userId, request.getSessionId(), request.getContent());
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
