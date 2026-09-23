package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.dataobject.MessageSession;
import com.example.chatroom.pojo.response.SessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageSessionMapper extends BaseMapper<MessageSession> {

    /**
     * 查某个用户的会话列表（含未读数）。
     * <p>
     * 这里 join 了两次 message_session_user：
     * <ul>
     *   <li>{@code me}    —— 我自己那一行，用来拿 last_read_message_id（未读游标）</li>
     *   <li>{@code other} —— 对方那一行，用来拿对方的昵称</li>
     * </ul>
     * 顺手去掉了原来 {@code where session_id in (子查询)} 的写法：
     * 既然 join 了 me，它本身就保证了"我必须是这个会话的成员"。
     * <p>
     * 未读数的定义：这个会话里 message_id > 我读到的位置、且不是我自己发的 消息条数。
     */
    @Select("select ms.session_id as sessionId, " +
            "       u.user_id    as friendId, " +
            "       u.user_name  as friendName, " +
            "       (select m.content from message m " +
            "         where m.session_id = ms.session_id " +
            "         order by m.post_time desc, m.message_id desc limit 1) as lastMessage, " +
            "       (select count(*) from message m2 " +
            "         where m2.session_id = ms.session_id " +
            "           and m2.from_id <> #{userId} " +
            "           and m2.message_id > me.last_read_message_id) as unreadCount " +
            "from message_session ms " +
            "join message_session_user me    on me.session_id = ms.session_id and me.user_id = #{userId} " +
            "join message_session_user other on other.session_id = ms.session_id and other.user_id <> #{userId} " +
            "join user u on u.user_id = other.user_id " +
            "order by ms.last_time desc")
    List<SessionRow> selectSessionList(@Param("userId") Integer userId);

    @Select("select msu1.session_id " +
            "from message_session_user msu1 " +
            "join message_session_user msu2 on msu1.session_id = msu2.session_id " +
            "where msu1.user_id = #{selfUserId} " +
            "  and msu2.user_id = #{toUserId} " +
            "limit 1")
    Integer findCommonSession(@Param("selfUserId") Integer selfUserId,
                              @Param("toUserId") Integer toUserId);
}
