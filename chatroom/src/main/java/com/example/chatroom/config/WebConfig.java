package com.example.chatroom.config;

import com.example.chatroom.common.interceptor.LogInterceptor;
import com.example.chatroom.common.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final LogInterceptor logInterceptor;

    public WebConfig(LoginInterceptor loginInterceptor, LogInterceptor logInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.logInterceptor = logInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 观测拦截器：拦全部路径，只打日志、不做鉴权
        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/**");

        // 2. 登录拦截器：默认全拦 + 显式放行。
        //    这样新增接口默认就是「需要登录」的，不会因为忘记加路径而漏掉鉴权。
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录 / 注册接口本身
                        "/user/login",
                        "/user/register",
                        // 静态页面：页面能否使用交给前端 JS 判断，避免浏览器直接跳到接口拿到一大坨 JSON
                        "/login.html",
                        "/register.html",
                        "/client.html",
                        // 静态资源
                        "/css/**",
                        "/js/**",
                        "/image/**",
                        "/favicon.ico",
                        // 关键：Controller 抛异常后 SpringBoot 会 forward 到 /error，
                        // 不排除的话这次内部转发会被再拦一次，401 会盖掉真正的错误
                        "/error",
                        // WebSocket 握手：它拿不到 User-Token 请求头，鉴权交给 AuthHandshakeInterceptor
                        // （token 挂在 URL query 上）。这里必须放行，否则会被当成普通请求判 401。
                        "/ws/**"
                );
    }
}
