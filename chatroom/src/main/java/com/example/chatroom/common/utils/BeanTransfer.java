package com.example.chatroom.common.utils;

import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.response.UserInfoResponse;

/**
 * 实体 -> 响应 DTO 的转换工具。
 * 目的：不让 Controller 直接返回实体，避免把 password 这类敏感字段暴露给前端。
 */
public class BeanTransfer {

    private BeanTransfer() {}

    /** User 实体 -> 当前登录用户信息响应 */
    public static UserInfoResponse toUserInfoResponse(User user) {
        if (user == null) {
            return null;
        }
        UserInfoResponse response = new UserInfoResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUserName());
        return response;
    }
}
