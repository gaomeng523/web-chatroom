package com.example.chatroom.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
public class Md5Util {
    private static final int MD5_LENGTH = 32;
    private static final int STORED_LENGTH = MD5_LENGTH * 2;

    public static String encrypt(String password) {
        String salt = UUID.randomUUID().toString().replace("-", "");
        String finalPassword = DigestUtils.md5DigestAsHex(
                (password + salt).getBytes(StandardCharsets.UTF_8));
        return finalPassword + salt;
    }

    public static Boolean verify(String inputPassword , String storedPassword) {
        if(!StringUtils.hasText(inputPassword) || !StringUtils.hasText(storedPassword)) {
            log.warn("密码校验失败：输入密码或数据库密码为空");
            return false;
        }

        if(storedPassword.length() != STORED_LENGTH) {
            log.warn("密码校验失败：密文长度不是 {} 位，实际 {} 位",
                    STORED_LENGTH, storedPassword.length());
            return false;
        }

        String finalPassword = storedPassword.substring(0, MD5_LENGTH);
        String salt =  storedPassword.substring(MD5_LENGTH);

        String computedPassword = DigestUtils.md5DigestAsHex((inputPassword + salt)
                .getBytes(StandardCharsets.UTF_8));

        return computedPassword.equals(finalPassword);
    }
}
