package com.example.chatroom.controller;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserInfoResponse;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;
import com.example.chatroom.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public UserLoginResponse login(@Validated @RequestBody UserLoginRequest userLoginRequest) {
        log.info("用户登录：username = {}", userLoginRequest.getUsername());
        return userService.login(userLoginRequest);
    }

    @PostMapping("/register")
    public UserRegisterResponse register(@Validated @RequestBody UserRegisterRequest userRegisterRequest) {
        log.info("用户注册：username = {}", userRegisterRequest.getUsername());
        return userService.register(userRegisterRequest);
    }

    /**
     * 获取当前登录用户信息。
     * <p>
     * 这里不需要任何入参：拦截器已经校验过 token，并把 userId 放进了 request 域。
     * 这也是走 JWT 方案时统一的做法 —— 当前用户身份只从拦截器来，绝不从请求参数取，
     * 否则前端只要改个 userId 就能看别人的信息。
     */
    @GetMapping("/userInfo")
    public UserInfoResponse getUserInfo(HttpServletRequest request) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        log.info("获取当前用户信息：userId = {}", userId);
        return userService.getUserInfo(userId);
    }
}
