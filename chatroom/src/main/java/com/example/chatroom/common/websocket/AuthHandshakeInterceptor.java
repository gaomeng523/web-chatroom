package com.example.chatroom.common.websocket;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebSocket 握手鉴权。
 * <p>
 * 浏览器原生 new WebSocket(url) 不支持自定义请求头，
 * 所以 {@code User-Token} 那套用不了，token 只能挂在 URL query 上：
 * {@code ws://host/ws/message?token=xxx}
 * <p>
 * 这里校验通过后，把当前用户信息塞进 WebSocketSession 的 attributes，
 * 端点在 afterConnectionEstablished / handleTextMessage 里就能直接取。
 */
@Slf4j
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    public AuthHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = parseToken(request.getURI());
        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 握手被拒：未携带 token, uri = {}", request.getURI());
            return reject(response, "未登录，请先登录");
        }

        Claims claims = jwtUtil.parseJwt(token);
        if (claims == null) {
            log.warn("WebSocket 握手被拒：token 无效或已过期");
            return reject(response, "登录已过期，请重新登录");
        }

        Object idObj = claims.get(Constant.JWT_CLAIM_ID);
        if (idObj == null) {
            log.warn("WebSocket 握手被拒：token 中缺少 {}", Constant.JWT_CLAIM_ID);
            return reject(response, "登录态异常，请重新登录");
        }

        attributes.put(Constant.CURRENT_USER_ID, Integer.valueOf(idObj.toString()));
        attributes.put(Constant.CURRENT_USER_NAME, claims.get(Constant.JWT_CLAIM_NAME, String.class));
        log.info("WebSocket 握手通过：userId = {}", idObj);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手成功后不需要额外处理
    }

    private boolean reject(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = "{\"code\":401,\"message\":\"" + message + "\"}";
            response.getBody().write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("写握手失败响应出错：{}", e.getMessage());
        }
        return false;
    }

    /**
     * 从 query 里取 token。
     * <p>
     * 手写而不用 UriComponentsBuilder：getURI() 拿到的是原始（已编码）URI，
     * UriComponentsBuilder 的 build()/build(encoded) 语义容易搞反，
     * 直接对 rawQuery 做 URLDecoder.decode 最稳妥。
     */
    private String parseToken(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (!StringUtils.hasText(rawQuery)) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && Constant.WS_TOKEN_PARAM.equals(pair.substring(0, idx))) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
