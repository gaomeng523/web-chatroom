package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.dataobject.MessageSessionUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageSessionUserMapper extends BaseMapper<MessageSessionUser> {

    /**
     * 把某个用户在某会话里的已读游标推到 messageId。
     * <p>
     * 关键在那个 {@code and last_read_message_id < #{messageId}}：
     * <ul>
     *   <li><b>幂等</b> —— 重复点同一个会话不会报错、也不会把游标推过头</li>
     *   <li><b>并发安全</b> —— 两个标签页同时上报，游标只会往前走、不会被旧值覆盖回去</li>
     * </ul>
     * 如果写成 {@code set last_read_message_id = #{messageId}}，
     * 一个慢请求后到就会把已读位置"倒退"，用户会看到已经读完的消息又变未读。
     *
     * @return 受影响行数，0 表示游标已经 >= messageId，无需更新
     */
    @Update("update message_session_user " +
            "set last_read_message_id = #{messageId} " +
            "where session_id = #{sessionId} " +
            "  and user_id = #{userId} " +
            "  and last_read_message_id < #{messageId}")
    int markRead(@Param("userId") Integer userId,
                 @Param("sessionId") Integer sessionId,
                 @Param("messageId") Integer messageId);
}
