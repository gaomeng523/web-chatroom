package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.dataobject.Message;
import com.example.chatroom.pojo.response.MessageResponse;
import com.example.chatroom.pojo.response.MessageSearchResponse;
import com.example.chatroom.pojo.response.UnreadCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 查某个会话的历史消息。
     * <p>
     * 排序必须加 message_id 兜底：post_time 是 datetime（无小数秒），
     * 同一秒内发的多条消息时间戳完全相同，只按 post_time 排结果是不确定的。
     * <p>
     * 已撤回的消息：{@code case when} 直接把 content 返回成 null。
     * 原文还在库里（方便事后核查），但既然撤回了就不该再下发到客户端。
     */
    @Select("select m.message_id as messageId, " +
            "       m.from_id    as fromId, " +
            "       u.user_name  as fromName, " +
            "       m.session_id as sessionId, " +
            "       case when m.revoked = 1 then null else m.content end as content, " +
            "       m.post_time  as postTime, " +
            "       m.revoked    as revoked, " +
            "       m.type       as type " +
            "from message m " +
            "join user u on m.from_id = u.user_id " +
            "where m.session_id = #{sessionId} " +
            "order by m.post_time asc, m.message_id asc")
    List<MessageResponse> selectHistoryMessage(@Param("sessionId") Integer sessionId);

    /** 某个会话当前最大的 message_id，空会话返回 0（用来"把这个会话全部标为已读"） */
    @Select("select coalesce(max(message_id), 0) from message where session_id = #{sessionId}")
    Integer selectMaxMessageId(@Param("sessionId") Integer sessionId);

    /**
     * 搜索当前用户所有会话里的消息。
     * <p>
     * 权限隔离靠 {@code join message_session_user msu on ... and msu.user_id = #{userId}}：
     * 只有"我确实是成员"的会话里的消息才会被 join 出来，
     * 所以搜不到别人的聊天 —— 这个 join 不是可选的优化，是安全边界。
     * <p>
     * 三个过滤条件：
     * <ul>
     *   <li>{@code m.type = 1} —— 只搜文本。图片消息的 content 是文件路径，搜出来没意义</li>
     *   <li>{@code m.revoked = 0} —— 已撤回的不该被搜出来，否则撤回就白撤了</li>
     *   <li>{@code limit 100} —— 别让一次搜索把整张表拖回来，真实项目这里要分页</li>
     * </ul>
     */
    @Select("select m.message_id as messageId, " +
            "       m.from_id    as fromId, " +
            "       u.user_name  as fromName, " +
            "       m.session_id as sessionId, " +
            "       m.content    as content, " +
            "       m.post_time  as postTime, " +
            "       ms.type      as sessionType, " +
            "       coalesce(ms.name, " +
            "                (select u2.user_name from message_session_user msu2 " +
            "                   join user u2 on u2.user_id = msu2.user_id " +
            "                  where msu2.session_id = ms.session_id " +
            "                    and msu2.user_id <> #{userId} " +
            "                  limit 1)) as sessionName " +
            "from message m " +
            "join message_session ms on ms.session_id = m.session_id " +
            "join user u on u.user_id = m.from_id " +
            "join message_session_user msu " +
            "  on msu.session_id = m.session_id and msu.user_id = #{userId} " +
            "where m.content like concat('%', #{keyword}, '%') " +
            "  and m.revoked = 0 " +
            "  and m.type = 1 " +
            "order by m.post_time desc, m.message_id desc " +
            "limit 100")
    List<MessageSearchResponse> selectSearchMessage(@Param("userId") Integer userId,
                                                    @Param("keyword") String keyword);

    /**
     * 把消息标记成已撤回。
     * <p>
     * 注意 where 里那三个条件，它们不是重复劳动，而是<b>防并发的最后一道闸</b>：
     * <ul>
     *   <li>{@code from_id = #{userId}} —— 只能撤自己发的</li>
     *   <li>{@code revoked = 0} —— 已经撤过的不能再撤（幂等）</li>
     *   <li>{@code post_time >= #{deadline}} —— 超过 2 分钟的不给撤</li>
     * </ul>
     * Service 里已经先 select 出来校验过一遍了，但那是"读"、这里是"写"，
     * 中间隔着时间窗：两个标签页同时点撤回，两边都能通过读校验，
     * 只有这条带条件的 UPDATE 能保证只有一次真正生效（返回 0 行的那次就是输了）。
     * <p>
     * 部署到多台机器、或者用户改系统时间时，也只信这里的服务端时间。
     *
     * @return 影响行数，0 表示没撤成功（不存在 / 不是你的 / 已撤过 / 超时）
     */
    @Update("update message set revoked = 1 " +
            "where message_id = #{messageId} " +
            "  and from_id = #{userId} " +
            "  and revoked = 0 " +
            "  and post_time >= #{deadline}")
    int revokeMessage(@Param("messageId") Integer messageId,
                      @Param("userId") Integer userId,
                      @Param("deadline") LocalDateTime deadline);

    /**
     * 一次算出「某个会话里每个成员」的未读条数。
     * <p>
     * 原来是按成员循环调一次单人版查询，群聊 50 个人发一条消息就要查 50 次（典型 N+1）。
     * 现在一条 group by 搞定，只返回"除发送者之外"的人 —— 发送者自己那条推送本来就不带未读数。
     * <p>
     * ⚠️ 两个容易写错的地方：
     * <ul>
     *   <li>必须 {@code left join} + {@code count(m.message_id)}。写成 {@code count(*)} 的话，
     *       没有未读的成员会因为 left join 补出一行 NULL 而被算成 1 条未读。</li>
     *   <li>过滤条件（不是我发的、id 大于我的游标）只能写在 {@code on} 里，
     *       写到 {@code where} 会把 left join 退化成 inner join，同样让"0 未读"的人直接消失。</li>
     * </ul>
     * <p>
     * 没排除已撤回的消息，理由同单人版：撤回在界面上仍占一条未读。
     */
    @Select("select msu.user_id as userId, " +
            "       count(m.message_id) as unreadCount " +
            "from message_session_user msu " +
            "left join message m " +
            "  on m.session_id = msu.session_id " +
            " and m.from_id <> msu.user_id " +
            " and m.message_id > msu.last_read_message_id " +
            "where msu.session_id = #{sessionId} " +
            "group by msu.user_id")
    List<UnreadCountRow> selectUnreadCounts(@Param("sessionId") Integer sessionId);
}
