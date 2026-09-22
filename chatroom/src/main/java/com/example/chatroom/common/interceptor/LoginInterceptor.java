package com.example.chatroom.common.interceptor;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 登录拦截器：从请求头解析 JWT，校验通过后把当前用户信息放进 request 域。
 */
@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public LoginInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        // 1. 取请求头中的 token
        String userToken = request.getHeader(Constant.USER_TOKEN_HEADER);
        if (!StringUtils.hasText(userToken)) {
            log.warn("请求被拦截：未携带 token, uri = {}", request.getRequestURI());
            return reject(response, "未登录，请先登录");
        }

        // 2. 解析 token（parseJwt 内部已捕获异常，失败返回 null）
        Claims claims = jwtUtil.parseJwt(userToken);
        if (claims == null) {
            log.warn("请求被拦截：token 无效或已过期, uri = {}", request.getRequestURI());
            return reject(response, "登录已过期，请重新登录");
        }

        // 3. 校验必要 claim。用 toString() 再转，避免 JSON 反序列化回 Integer/Long 时强转失败
        Object idObj = claims.get(Constant.JWT_CLAIM_ID);
        if (idObj == null) {
            log.warn("请求被拦截：token 中缺少 {} , uri = {}", Constant.JWT_CLAIM_ID, request.getRequestURI());
            return reject(response, "登录态异常，请重新登录");
        }
        Integer userId = Integer.valueOf(idObj.toString());

        // 4. 放进 request 域，后续 Controller 用 Constant.CURRENT_USER_ID / CURRENT_USER_NAME 取值
        request.setAttribute(Constant.CURRENT_USER_ID, userId);
        request.setAttribute(Constant.CURRENT_USER_NAME,
                claims.get(Constant.JWT_CLAIM_NAME, String.class));

        log.info("登录校验通过，userId:{}, uri:{}, method:{}",
                userId, request.getRequestURI(), request.getMethod());
        return true;
    }

    /**
     * 统一返回 401 + JSON 响应体，前端可在 error 回调里读到 message。
     * 返回 false 是为了让调用处写成 return reject(...)，一行收敛。
     */
    private boolean reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
        return false;
    }
}
