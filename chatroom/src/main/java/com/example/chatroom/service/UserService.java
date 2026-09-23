package com.example.chatroom.service;

import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserInfoResponse;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;

public interface UserService {
    UserLoginResponse login(UserLoginRequest userLoginRequest);

    UserRegisterResponse register(UserRegisterRequest userRegisterRequest);

    /** 根据当前登录用户 id 查询用户信息 */
    UserInfoResponse getUserInfo(Integer userId);
}
