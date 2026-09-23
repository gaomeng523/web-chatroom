package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.dataobject.Message;
import com.example.chatroom.pojo.response.MessageResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 查某个会话的历史消息。
     * <p>
     * 排序必须加 message_id 兜底：post_time 是 datetime（无小数秒），
     * 同一秒内发的多条消息时间戳完全相同，只按 post_time 排结果是不确定的。
     */
    @Select("select m.message_id as messageId, " +
            "       m.from_id    as fromId, " +
            "       u.user_name  as fromName, " +
            "       m.session_id as sessionId, " +
            "       m.content    as content, " +
            "       m.post_time  as postTime " +
            "from message m " +
            "join user u on m.from_id = u.user_id " +
            "where m.session_id = #{sessionId} " +
            "order by m.post_time asc, m.message_id asc")
    List<MessageResponse> selectHistoryMessage(@Param("sessionId") Integer sessionId);

    /** 某个会话当前最大的 message_id，空会话返回 0（用来"把这个会话全部标为已读"） */
    @Select("select coalesce(max(message_id), 0) from message where session_id = #{sessionId}")
    Integer selectMaxMessageId(@Param("sessionId") Integer sessionId);

    /**
     * 算某个用户在某会话里的未读数。
     * <p>
     * 判"未读"只看两个条件：这条消息不是我发的、且它的 message_id 超过了我的已读游标。
     * 用 message_id 而不是 post_time 比：自增主键严格单调，
     * 而 post_time 是 datetime（无小数秒），同秒的消息分不出先后。
     * <p>
     * 主表是 message，所以放在 MessageMapper 里。
     */
    @Select("select count(*) from message m " +
            "join message_session_user msu " +
            "  on msu.session_id = m.session_id and msu.user_id = #{userId} " +
            "where m.session_id = #{sessionId} " +
            "  and m.from_id <> #{userId} " +
            "  and m.message_id > msu.last_read_message_id")
    Integer selectUnreadCount(@Param("userId") Integer userId, @Param("sessionId") Integer sessionId);
}
