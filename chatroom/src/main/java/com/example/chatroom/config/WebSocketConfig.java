package com.example.chatroom.config;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.websocket.AuthHandshakeInterceptor;
import com.example.chatroom.common.websocket.MessageWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
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
