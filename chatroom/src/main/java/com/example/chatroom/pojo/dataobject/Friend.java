package com.example.chatroom.pojo.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class Friend {
    private Integer friendId;
    private String friendName;
}
