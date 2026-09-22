package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.utils.JwtUtil;
import com.example.chatroom.common.utils.Md5Util;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;
import com.example.chatroom.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public UserLoginResponse login(UserLoginRequest userLoginRequest) {
        User user = getUserByname(userLoginRequest.getUsername());
        // 用户不存在与密码错误返回同一提示，避免被用于枚举用户名
        if (user == null || !Md5Util.verify(userLoginRequest.getPassword(), user.getPassword())) {
            throw new UserException("用户名或密码错误");
        }

        Map<String, Object> map = new HashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUserName());
        String token = jwtUtil.genJwt(map);

        log.info("登录成功: userId:{}", user.getUserId());
        return new UserLoginResponse(user.getUserId(), token);
    }

    @Override
    public UserRegisterResponse register(UserRegisterRequest userRegisterRequest) {
        String username = userRegisterRequest.getUsername();
        // 先查一次给出友好提示，并发下的漏网之鱼由唯一索引兜底
        if (getUserByname(username) != null) {
            throw new UserException("用户名已存在");
        }

        User user = new User();
        user.setUserName(username);
        user.setPassword(Md5Util.encrypt(userRegisterRequest.getPassword()));

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            log.warn("注册失败：用户名已存在，username = {}", username);
            throw new UserException("用户名已存在");
        }

        // insert 后 MyBatis-Plus 会把自增主键回填到 userInfo.userId
        log.info("注册成功: userId:{}, username:{}", user.getUserId(), username);
        return new UserRegisterResponse(user.getUserId(), username);
    }

    private User getUserByname(String userName) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserName, userName));
    }
}
