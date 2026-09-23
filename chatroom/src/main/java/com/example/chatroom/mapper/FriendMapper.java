package com.example.chatroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.dataobject.FriendRelation;
import com.example.chatroom.pojo.response.FriendRequestResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FriendMapper extends BaseMapper<FriendRelation> {
    @Select("select u.user_id as friendId, u.user_name as friendName " +
            "from friend f join user u on f.friend_id = u.user_id " +
            "where f.user_id = #{userId}")
    List<Friend> selectFriendList(@Param("userId") Integer userId);

    @Select("select user_id as friendId, user_name as friendName from user " +
            "where user_id != #{selfUserId} " +
            "and user_name like concat('%', #{name}, '%') " +
            "and user_id not in (select friend_id from friend where user_id = #{selfUserId})")
    List<Friend> findFriend(@Param("selfUserId") Integer selfUserId,
                            @Param("name") String name);

    @Select("select r.from_user_id as fromUserId, u.user_name as fromUserName, r.reason " +
            "from add_friend_request r join user u on r.from_user_id = u.user_id " +
            "where r.to_user_id = #{userId}")
    List<FriendRequestResponse> getFriendRequest(@Param("userId") Integer userId);

    @Insert("insert ignore into friend (user_id, friend_id) values (#{userId}, #{friendId})")
    int insertIgnoreRelation(@Param("userId") Integer userId, @Param("friendId") Integer friendId);
}
