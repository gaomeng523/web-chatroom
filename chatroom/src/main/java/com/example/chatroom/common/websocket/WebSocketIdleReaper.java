package com.example.chatroom.common.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 空闲连接回收。
 * <p>
 * 线上最容易被忽略的一种"在线用户"：用户拔了网线、笔记本合盖休眠、手机从 WiFi 走到 4G ——
 * 这些情况下 TCP 连 FIN 都收不到，{@code afterConnectionClosed} 根本不会被回调，
 * 连接就一直挂在 {@link OnlineUserManager} 里。<b>内存只涨不降，isOnline() 撒谎，
 * 推送给他的消息全打到空气上。</b>
 * <p>
 * 靠客户端重连治不了这个：客户端重连是开一条<b>新</b>连接，老的那条还在服务端挂着。
 * 所以必须有服务端自己的一遍扫描。
 * <p>
 * 判活依据是客户端每 25s 一次的应用层心跳 —— 心跳到了 {@code handleTextMessage} 就会
 * {@code touch()} 一次，所以正常连接永远不会被这里关掉。
 */
@Slf4j
@Component
public class WebSocketIdleReaper {

    private final OnlineUserManager onlineUserManager;

    /**
     * 多久没收到任何帧就算这条连接死了。
     * <p>
     * 必须明显大于客户端心跳间隔（client.js 的 WS_HEARTBEAT_MS = 25s）——
     * 至少要能容忍两三次心跳丢失（网络抖动、GC 停顿），否则会把好连接误杀。
     * 120s ≈ 4 个心跳周期。
     */
    @Value("${websocket.session-idle-timeout-ms:120000}")
    private long idleTimeoutMs;

    public WebSocketIdleReaper(OnlineUserManager onlineUserManager) {
        this.onlineUserManager = onlineUserManager;
    }

    /**
     * 扫描周期。用 fixedDelay 而不是 fixedRate：fixedRate 会在上一轮没跑完时堆叠执行，
     * 而这里每一轮都可能去关连接（有 IO），本该一轮一轮来。
     */
    @Scheduled(fixedDelayString = "${websocket.reap-interval-ms:30000}")
    public void reapIdleSessions() {
        int closed = onlineUserManager.closeIdleSessions(idleTimeoutMs);
        if (closed > 0) {
            log.info("WebSocket 空闲回收：关闭 {} 条无响应连接，剩余在线连接 {}", closed, onlineUserManager.sessionCount());
        }
    }
}
