package com.example.chatroom.service;

import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserInfoResponse;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    UserLoginResponse login(UserLoginRequest userLoginRequest);

    UserRegisterResponse register(UserRegisterRequest userRegisterRequest);

    /** 根据当前登录用户 id 查询用户信息 */
    UserInfoResponse getUserInfo(Integer userId);

    /**
     * 更换头像，返回新的头像 URL（形如 /upload/avatar/4_9f2c.png）
     */
    String updateAvatar(Integer userId, MultipartFile file);

    /**
     * 读取头像文件，没设置过头像（或文件丢了）返回 null
     */
    Resource loadAvatar(Integer userId);
}
