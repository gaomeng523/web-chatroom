package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.request.AddFriendRequest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * add_friend_request 表的 Mapper。
 * <p>
 * 为什么不复用 FriendMapper：FriendMapper 继承的是 BaseMapper&lt;FriendRelation&gt;，
 * 它的 delete / selectCount 只接受 Wrapper&lt;FriendRelation&gt;，
 * 拿它删 add_friend_request 表会编译不过。每张表对应一个自己的 Mapper。
 */
@Mapper
public interface AddFriendRequestMapper extends BaseMapper<AddFriendRequest> {

    /**
     * 插入好友请求，重复发请求时静默忽略。
     * <p>
     * MP 的 insert 遇到主键冲突会抛 DuplicateKeyException，
     * 所以这里用 insert ignore 自己写。
     */
    @Insert("insert ignore into add_friend_request (from_user_id, to_user_id, reason) " +
            "values (#{fromUserId}, #{toUserId}, #{reason})")
    int insertIgnoreRequest(@Param("fromUserId") Integer fromUserId,
                            @Param("toUserId") Integer toUserId,
                            @Param("reason") String reason);
}
