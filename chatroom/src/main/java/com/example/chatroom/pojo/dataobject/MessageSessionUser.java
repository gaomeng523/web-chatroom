package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_session_user")
public class MessageSessionUser {
    private Integer sessionId;
    private Integer userId;

    /**
     * 已读游标：该用户在这个会话里已读到的最大 message_id，0 表示全未读。
     * 未读数 = 这个会话里 message_id > 它的、且不是自己发的 消息条数。
     */
    private Integer lastReadMessageId;
}
