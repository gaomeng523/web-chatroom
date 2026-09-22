package com.example.chatroom.pojo.dataobject;

import lombok.Data;

@Data
public class User {
    private Integer userId;
    private String userName;
    private String password;
}
