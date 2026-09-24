package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.utils.BeanTransfer;
import com.example.chatroom.common.utils.JwtUtil;
import com.example.chatroom.common.utils.Md5Util;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.request.UserLoginRequest;
import com.example.chatroom.pojo.request.UserRegisterRequest;
import com.example.chatroom.pojo.response.UserInfoResponse;
import com.example.chatroom.pojo.response.UserLoginResponse;
import com.example.chatroom.pojo.response.UserRegisterResponse;
import com.example.chatroom.service.FileStorageService;
import com.example.chatroom.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final FileStorageService fileStorageService;

    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, FileStorageService fileStorageService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public UserLoginResponse login(UserLoginRequest userLoginRequest) {
        User user = getUserByname(userLoginRequest.getUsername());
        // 用户不存在与密码错误返回同一提示，避免被用于枚举用户名
        if (user == null || !Md5Util.verify(userLoginRequest.getPassword(), user.getPassword())) {
            throw new UserException("用户名或密码错误");
        }

        Map<String, Object> map = new HashMap<>();
        map.put(Constant.JWT_CLAIM_ID, user.getUserId());
        map.put(Constant.JWT_CLAIM_NAME, user.getUserName());
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

    @Override
    public UserInfoResponse getUserInfo(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户被删了、或 token 里的 id 已经失效
            throw new UserException("用户不存在");
        }
        return BeanTransfer.toUserInfoResponse(user);
    }

    @Override
    public String updateAvatar(Integer userId, MultipartFile file) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserException("用户不存在");
        }

        String url = fileStorageService.saveImage(file, Constant.AVATAR_DIR, userId);

        // 只更新 avatar 一个字段：MP 的 updateById 只把非 null 字段拼进 SQL，
        // 所以不会被 password / user_name 覆盖（前提是这里别手贱去 set 它们）。
        User update = new User();
        update.setUserId(userId);
        update.setAvatar(url);
        userMapper.updateById(update);

        // 旧头像文件顺手删掉，否则换 10 次头像就有 10 个再也不会被访问的垃圾文件。
        // 放在 update 之后删：万一删旧文件出了岔子，新头像也已经生效了，
        // 不会出现"旧的删了、新的没存上"这种最差情况。
        fileStorageService.deleteQuietly(user.getAvatar());

        log.info("头像已更新：userId = {}, url = {}", userId, url);
        return url;
    }

    @Override
    public Resource loadAvatar(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getAvatar() == null) {
            return null;
        }
        return fileStorageService.loadAsResource(user.getAvatar());
    }

    private User getUserByname(String userName) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserName, userName));
    }
}
