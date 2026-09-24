package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 message_session 表。
 * 单聊和群聊共用这张表，靠 type 区分；谁是成员在 message_session_user 里
 * （单聊 2 行、群聊 N 行，所以成员表天然就支持群聊）。
 */
@Data
@TableName("message_session")
public class MessageSession {

    @TableId(type = IdType.AUTO)
    private Integer sessionId;

    /** 1 单聊 / 2 群聊，取值见 Constant.SESSION_TYPE_* */
    private Integer type;

    /** 群名称。单聊为 NULL —— 单聊的标题是"对方的昵称"，那是查出来的，不是存出来的 */
    private String name;

    /**
     * 最后一条消息的时间，会话列表按它倒序排。
     * 必须加 @JsonFormat：Jackson 对 LocalDateTime 默认输出 2026-09-23T22:53:33，
     * 而前端 formatTime 是靠空格切分的，会走到 else 分支显示原始格式。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTime;
}
