package com.example.chatroom.common.exception;

import lombok.Getter;

@Getter
public class UserException extends RuntimeException {
    private final Integer code;
    private final String message;

    public UserException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public UserException(String message) {
        this.code = 400;
        this.message = message;
    }
}
