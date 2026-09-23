package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("friend")
public class FriendRelation {
    private Integer userId;
    private Integer friendId;
}
