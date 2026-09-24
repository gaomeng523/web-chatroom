package com.example.chatroom.common.websocket;

import com.example.chatroom.common.constant.Constant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        if (userId == null || session == null) {
            return;
        }
        onlineSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        touch(session);
        log.info("用户上线：userId = {}, 当前在线连接数 = {}", userId, sessionCount());
    }

    /**
     * 标记"这条连接刚刚还活着"。每收到一帧就调一次（客户端心跳 25s 一次，顺带就把时间刷了）。
     * <p>
     * 时间戳直接存在 {@code session.getAttributes()} 里，而不是另开一张 Map 去维护 ——
     * 少一份和 Set 同步的状态，就少一处能被写歪的地方；连接被摘掉时时间戳跟着一起消失。
     */
    public void touch(WebSocketSession session) {
        if (session == null) {
            return;
        }
        try {
            session.getAttributes().put(Constant.WS_SESSION_LAST_ACTIVE, System.currentTimeMillis());
        } catch (Exception e) {
            // 连接刚好在这会儿被关掉，attributes 可能已经不可写 —— 只是少一次刷新，不必惊动调用方
            log.debug("刷新连接活跃时间失败：{}", e.getMessage());
        }
    }

    /** 该连接最后一次活跃的时间；拿不到就当成"就是现在"，避免误杀 */
    private long lastActiveAt(WebSocketSession session) {
        Object v = session.getAttributes().get(Constant.WS_SESSION_LAST_ACTIVE);
        return (v instanceof Long l) ? l : System.currentTimeMillis();
    }

    /**
     * 关掉所有"超过 {@code timeoutMs} 没收到任何帧"的连接，返回关掉的条数。
     * <p>
     * 为什么要自己扫：用户拔网线、笔记本睡眠时 TCP 收不到 FIN，{@code afterConnectionClosed}
     * 根本不会被回调，这条连接就永远赖在 {@code onlineSessions} 里 ——
     * 内存只涨不降，{@code isOnline()} 撒谎，推送全打到空气上。
     * <p>
     * ⚠️ 这里刻意<b>没有</b>用 Tomcat 的 {@code ServerContainer.setDefaultMaxSessionIdleTimeout}：
     * 那套写法（含官方文档推荐的 {@code ServletServerContainerFactoryBean}）在
     * Spring Boot 3 + 内嵌 Tomcat 下实测<b>不生效</b> ——
     * 值确实设进去了（启动日志能看到"设置后读回 4000 ms"），但连接静置 6.5s 照样活着，
     * 因为 Spring 的 {@code WebSocketConfigurer} 握手路径不会把容器的默认值落到 session 上。
     * 与其依赖一个静默失效的开关，不如自己扫 —— 行为确定，而且能测。
     */
    public int closeIdleSessions(long timeoutMs) {
        if (timeoutMs <= 0) {
            return 0;   // 0 = 不回收
        }
        long deadline = System.currentTimeMillis() - timeoutMs;
        int closed = 0;

        // 遍历时先复制一份 key 和 set，避免和并发的 add/remove 互相打扰
        for (Map.Entry<Integer, Set<WebSocketSession>> entry : new ArrayList<>(onlineSessions.entrySet())) {
            Integer userId = entry.getKey();
            for (WebSocketSession session : new ArrayList<>(entry.getValue())) {
                if (lastActiveAt(session) >= deadline) {
                    continue;
                }
                log.info("回收空闲连接：userId = {}, sessionId = {}", userId, session.getId());
                try {
                    session.close(CloseStatus.GOING_AWAY);
                } catch (Exception e) {
                    log.warn("关闭空闲连接失败：userId = {}, 原因 = {}", userId, e.getMessage());
                }
                // 兜底：close() 正常会触发 afterConnectionClosed -> remove()，
                // 但如果那条回调没来，这里也得把它从在线表里摘掉，否则"回收"就成了空话。
                // 先判一下还在不在，免得和回调里那次 remove 一起打出两条"用户下线"的日志。
                if (isTracked(userId, session)) {
                    remove(userId, session);
                }
                closed++;
            }
        }
        return closed;
    }

    /**
     * 摘掉一条连接。
     * <p>
     * ⚠️ 必须用 {@code computeIfPresent} 把「移除连接」和「清掉空的 map entry」做成<b>一次原子操作</b>。
     * 原来拆成三步：
     * <pre>{@code
     * sessions.remove(session);
     * if (sessions.isEmpty()) onlineSessions.remove(userId);   // ← 不是原子的
     * }</pre>
     * 多标签页同时刷新时会出现经典竞态：A 删掉最后一个 session、判断 isEmpty 得到 true 的瞬间，
     * B 往同一个 Set 里 add 了新连接，紧接着 A 把整个 map entry 删掉 ——
     * 结果 <b>B 的连接还活着，用户却被标记成离线，永久收不到任何推送</b>（直到他再刷新一次页面）。
     */
    public void remove(Integer userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        onlineSessions.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;   // 返回 null = 删掉这个 key
        });
        // 该用户所有标签页都关了才算是下线（日志可能略有偏差，但只是日志，无所谓）
        if (!onlineSessions.containsKey(userId)) {
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
        // 已经关掉的连接先收集起来，循环结束后统一摘掉。
        // 不能在遍历里直接 remove：虽然 ConcurrentHashMap 的 keySet 是弱一致的、
        // 边遍历边删不会抛 ConcurrentModificationException，但那样读起来很吓人。
        List<WebSocketSession> dead = null;

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                // ⚠️ 不能只 continue 就完事。清除死连接的唯一时机本来是 afterConnectionClosed，
                // 一旦它因为异常没被回调（或者 handleTransportError 之后直接失联），
                // 这个 session 就会永久留在 Set 里 —— 既占内存，又让 isOnline() 永远返回 true。
                // 所以顺手在这里自愈一次。
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(session);
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
                    // 发送时报错也说明这条连接已经不可用了，一并清掉
                    if (dead == null) {
                        dead = new ArrayList<>();
                    }
                    dead.add(session);
                }
            }
        }

        if (dead != null) {
            for (WebSocketSession session : dead) {
                remove(userId, session);
            }
        }
        return sent;
    }

    public boolean isOnline(Integer userId) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /** 这条连接还挂在在线表里吗 */
    private boolean isTracked(Integer userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        return sessions != null && sessions.contains(session);
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
