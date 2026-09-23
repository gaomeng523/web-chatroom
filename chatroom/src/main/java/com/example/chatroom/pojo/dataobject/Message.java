package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表实体。
 * 库中列名已统一为蛇形（message_id / from_id / session_id / content / post_time）。
 */
@Data
@TableName("message")
public class Message {

    /** @TableId(AUTO) 必须有，否则 insert 后 MP 不回填自增主键 */
    @TableId(type = IdType.AUTO)
    private Integer messageId;

    private Integer fromId;

    private Integer sessionId;

    private String content;

    /** 库里是 datetime（无小数秒），同一秒内的多条消息排序要靠 messageId 兜底 */
    private LocalDateTime postTime;
}
