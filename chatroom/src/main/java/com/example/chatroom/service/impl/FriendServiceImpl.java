package com.example.chatroom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.common.websocket.OnlineUserManager;
import com.example.chatroom.mapper.AddFriendRequestMapper;
import com.example.chatroom.mapper.FriendMapper;
import com.example.chatroom.mapper.UserMapper;
import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.dataobject.FriendRelation;
import com.example.chatroom.pojo.dataobject.User;
import com.example.chatroom.pojo.request.AddFriendRequest;
import com.example.chatroom.pojo.response.FriendRequestResponse;
import com.example.chatroom.pojo.response.WsMessageResponse;
import com.example.chatroom.service.FriendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
public class FriendServiceImpl implements FriendService {

    private final FriendMapper friendMapper;
    private final AddFriendRequestMapper addFriendRequestMapper;
    private final UserMapper userMapper;
    private final OnlineUserManager onlineUserManager;

    public FriendServiceImpl(FriendMapper friendMapper,
                             AddFriendRequestMapper addFriendRequestMapper,
                             UserMapper userMapper,
                             OnlineUserManager onlineUserManager) {
        this.friendMapper = friendMapper;
        this.addFriendRequestMapper = addFriendRequestMapper;
        this.userMapper = userMapper;
        this.onlineUserManager = onlineUserManager;
    }

    @Override
    public List<Friend> getFriendList(Integer userId) {
        return friendMapper.selectFriendList(userId);
    }

    @Override
    public List<Friend> findFriend(Integer selfUserId, String name) {
        if(!StringUtils.hasText(name)){
            throw new UserException("搜索关键字不能为空");
        }
        return friendMapper.findFriend(selfUserId, name);
    }

    @Override
    public void addFriend(Integer fromUserId, Integer toFriendId, String reason) {
        if(fromUserId.equals(toFriendId)) {
            throw new UserException("不能添加自己为好友");
        }
        User target = userMapper.selectById(toFriendId);
        if(target == null) {
            throw new UserException("用户不存在");
        }

        LambdaQueryWrapper<FriendRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FriendRelation::getUserId, fromUserId)
                .eq(FriendRelation::getFriendId, toFriendId);
        if(friendMapper.selectCount(wrapper) > 0) {
            throw new UserException("你们已经是好友了");
        }

        addFriendRequestMapper.insertIgnoreRequest(fromUserId, toFriendId, reason);
        log.info("好友请求已发送 ： from = {} to = {}", fromUserId, toFriendId);

        // 实时推给对方，对方页面右上角"新的朋友"立刻出现一条，不用手动刷新
        User from = userMapper.selectById(fromUserId);
        boolean pushed = onlineUserManager.sendTo(toFriendId, WsMessageResponse.ofAddFriendRequest(
                fromUserId, from == null ? "未知用户" : from.getUserName(), reason));
        if (!pushed) {
            log.info("接收方 userId = {} 当前不在线，好友请求等其上线后通过 /getFriendRequest 拉取", toFriendId);
        }
    }

    @Override
    public List<FriendRequestResponse> getFriendRequest(Integer userId) {
        return friendMapper.getFriendRequest(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptFriend(Integer selfUserId, Integer fromUserId) {
        // 双向插入，用 insert ignore 防重复接受时的主键冲突
        friendMapper.insertIgnoreRelation(selfUserId, fromUserId);
        friendMapper.insertIgnoreRelation(fromUserId, selfUserId);
        // 删掉这条请求
        LambdaQueryWrapper<AddFriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddFriendRequest::getFromUserId, fromUserId)
                .eq(AddFriendRequest::getToUserId, selfUserId);
        addFriendRequestMapper.delete(wrapper);
        log.info("已接受好友请求：{} <-> {}", selfUserId, fromUserId);

        // 推给申请人：对方能立刻看到"XX 已通过你的好友申请"，并自动刷新好友列表
        User self = userMapper.selectById(selfUserId);
        onlineUserManager.sendTo(fromUserId, WsMessageResponse.ofAcceptFriend(
                self == null ? "未知用户" : self.getUserName()));
    }

    @Override
    public void rejectFriend(Integer selfUserId, Integer fromUserId) {
        LambdaQueryWrapper<AddFriendRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddFriendRequest::getFromUserId, fromUserId)
                .eq(AddFriendRequest::getToUserId, selfUserId);
        addFriendRequestMapper.delete(wrapper);
        log.info("已拒绝好友请求：{} -> {}", fromUserId, selfUserId);
    }
}
