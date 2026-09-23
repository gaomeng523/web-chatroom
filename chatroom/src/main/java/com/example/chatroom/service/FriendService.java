package com.example.chatroom.service;

import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.response.FriendRequestResponse;

import java.util.List;

public interface FriendService {

    /**
     * 查某人的好友列表（联表查出好友的 id 和名字）
     */
    List<Friend> getFriendList(Integer userId);

    /**
     * 按名字模糊搜索可添加的用户（排除自己、排除已经是好友的）
     */
    List<Friend> findFriend(Integer selfUserId, String name);

    /**
     * 发起好友请求
     */
    void addFriend(Integer fromUserId, Integer toFriendId, String reason);

    /**
     * 查"别人发给我的"好友请求列表
     */
    List<FriendRequestResponse> getFriendRequest(Integer userId);

    /**
     * 接受好友请求：双向建立关系 + 删掉请求
     */
    void acceptFriend(Integer selfUserId, Integer fromUserId);

    /**
     * 拒绝好友请求：只删掉请求
     */
    void rejectFriend(Integer selfUserId, Integer fromUserId);
}
