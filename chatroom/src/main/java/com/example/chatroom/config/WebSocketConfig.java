package com.example.chatroom.config;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.websocket.AuthHandshakeInterceptor;
import com.example.chatroom.common.websocket.MessageWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册。
 * <p>
 * 这里挂 {@code @EnableScheduling} 是为了 {@code WebSocketIdleReaper} 的定时扫描 ——
 * 它扫的是 WebSocket 连接的空闲状态，放在这个配置类上比丢进启动类更好找。
 * <p>
 * ⚠️ 别再往这里加 {@code ServletServerContainerFactoryBean} 去设空闲超时。
 * 那是官方文档给的写法，但在 Spring Boot 3 + 内嵌 Tomcat 下<b>实测不生效</b>：
 * 值能设进 {@code WsServerContainer}（启动日志会打印"设置后读回 4000 ms"），
 * 但连接静置 6.5s 依然活着 —— Spring 的 {@code WebSocketConfigurer} 握手路径
 * 不会把容器的 defaultMaxSessionIdleTimeout 落到 session 上。
 * 真正生效的是自研的 {@code WebSocketIdleReaper}。
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final MessageWebSocketHandler messageWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;

    public WebSocketConfig(MessageWebSocketHandler messageWebSocketHandler,
                           AuthHandshakeInterceptor authHandshakeInterceptor) {
        this.messageWebSocketHandler = messageWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(messageWebSocketHandler, Constant.WS_MESSAGE_PATH)
                // 握手鉴权：token 从 URL query 取
                .addInterceptors(authHandshakeInterceptor)
                // 本地开发放行跨域；上线时换成具体域名
                .setAllowedOriginPatterns("*");
    }
}
