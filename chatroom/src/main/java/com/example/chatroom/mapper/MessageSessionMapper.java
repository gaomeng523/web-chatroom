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
            "       ms.type      as sessionType, " +
            "       ms.name      as sessionName, " +
            "       u.user_id    as friendId, " +
            "       u.user_name  as friendName, " +
            "       (select count(*) from message_session_user m3 " +
            "         where m3.session_id = ms.session_id) as memberCount, " +
            "       (select m.content from message m " +
            "         where m.session_id = ms.session_id " +
            "         order by m.post_time desc, m.message_id desc limit 1) as lastMessage, " +
            "       (select m.revoked from message m " +
            "         where m.session_id = ms.session_id " +
            "         order by m.post_time desc, m.message_id desc limit 1) as lastRevoked, " +
            "       (select m.type from message m " +
            "         where m.session_id = ms.session_id " +
            "         order by m.post_time desc, m.message_id desc limit 1) as lastType, " +
            "       (select u2.user_name from message m " +
            "          join user u2 on u2.user_id = m.from_id " +
            "         where m.session_id = ms.session_id " +
            "         order by m.post_time desc, m.message_id desc limit 1) as lastMessageFrom, " +
            "       (select count(*) from message m2 " +
            "         where m2.session_id = ms.session_id " +
            "           and m2.from_id <> #{userId} " +
            "           and m2.message_id > me.last_read_message_id) as unreadCount " +
            "from message_session ms " +
            "join message_session_user me on me.session_id = ms.session_id and me.user_id = #{userId} " +
            // 必须是 left join：群里其他人都退光时（只剩我），other 那行不存在。
            // 用 inner join 的话这个群会整行查不出来，等于从会话列表里凭空消失。
            "left join message_session_user other on other.session_id = ms.session_id and other.user_id <> #{userId} " +
            "left join user u on u.user_id = other.user_id " +
            "order by ms.last_time desc")
    List<SessionRow> selectSessionList(@Param("userId") Integer userId);

    /**
     * 找我和某人之间的**单聊**会话。
     * <p>
     * ⚠️ 必须带上 {@code ms.type = 1}：我和张三可能既有单聊又同在一个群，
     * 群成员表里我俩都是成员，不筛类型的话这里会把群聊的 sessionId 返回，
     * 结果点"私聊张三"打开的却是群。
     */
    @Select("select msu1.session_id " +
            "from message_session_user msu1 " +
            "join message_session_user msu2 on msu1.session_id = msu2.session_id " +
            "join message_session ms on ms.session_id = msu1.session_id " +
            "where msu1.user_id = #{selfUserId} " +
            "  and msu2.user_id = #{toUserId} " +
            "  and ms.type = 1 " +
            "limit 1")
    Integer findCommonSession(@Param("selfUserId") Integer selfUserId,
                              @Param("toUserId") Integer toUserId);
}
