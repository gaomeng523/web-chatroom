package com.example.chatroom.common.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线用户管理器：维护 userId -> WebSocket 连接 的映射，并提供定向推送。
 * <p>
 * 为什么 value 是 Set 而不是单个 session：同一个用户可能开着多个标签页，
 * 每个标签页都是一条独立连接，只存一个会把前面的连接"顶掉"、后面的收不到推送。
 */
@Slf4j
@Component
public class OnlineUserManager {

    private final Map<Integer, Set<WebSocketSession>> onlineSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /**
     * 必须用 Spring 容器里的 ObjectMapper。
     * 自己 new ObjectMapper() 没有注册 JavaTimeModule，
     * 序列化 LocalDateTime（postTime 字段）时会直接抛 InvalidDefinitionException。
     */
    public OnlineUserManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void add(Integer userId, WebSocketSession session) {
        onlineSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("用户上线：userId = {}, 当前在线连接数 = {}", userId, sessionCount());
    }

    public void remove(Integer userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        // 该用户所有标签页都关了才把他从在线表里摘掉
        if (sessions.isEmpty()) {
            onlineSessions.remove(userId);
            log.info("用户下线：userId = {}, 当前在线连接数 = {}", userId, sessionCount());
        }
    }

    /**
     * 给指定用户推送一条消息。
     *
     * @return 是否至少成功发出一条；用户不在线（或所有连接都已断）返回 false
     */
    public boolean sendTo(Integer userId, Object payload) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("推送内容序列化失败", e);
            return false;
        }

        TextMessage textMessage = new TextMessage(json);
        boolean sent = false;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            // WebSocketSession.sendMessage 不是线程安全的：同一个连接上并发发送会抛
            // "The remote endpoint was in state [TEXT_PARTIAL_WRITING]"，
            // 所以对同一个 session 加锁串行化。
            synchronized (session) {
                try {
                    session.sendMessage(textMessage);
                    sent = true;
                } catch (IOException | IllegalStateException e) {
                    log.warn("推送失败：userId = {}, 原因 = {}", userId, e.getMessage());
                }
            }
        }
        return sent;
    }

    public boolean isOnline(Integer userId) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /** 在线用户 id 集合（只读快照） */
    public Set<Integer> onlineUserIds() {
        return Collections.unmodifiableSet(onlineSessions.keySet());
    }

    /** 当前在线连接总数，只用来打日志 */
    public int sessionCount() {
        int count = 0;
        for (Set<WebSocketSession> sessions : onlineSessions.values()) {
            count += sessions.size();
        }
        return count;
    }
}
