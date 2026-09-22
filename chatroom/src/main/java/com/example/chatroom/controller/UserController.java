package com.example.chatroom.controller;

import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;
import com.example.chatroom.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
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
}
