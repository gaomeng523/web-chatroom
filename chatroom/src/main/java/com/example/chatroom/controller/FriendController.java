package com.example.chatroom.controller;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.pojo.dataobject.Friend;
import com.example.chatroom.pojo.response.FriendRequestResponse;
import com.example.chatroom.service.FriendService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class FriendController {
    @Autowired
    private FriendService friendService;

    private Integer currentUserId(HttpServletRequest request) {
        return (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
    }

    @GetMapping("/friendList")
    public List<Friend> getFriendList(HttpServletRequest request) {
        return friendService.getFriendList(currentUserId(request));
    }

    @GetMapping("/findFriend")
    public List<Friend> findFriend(HttpServletRequest request, String name) {
        return friendService.findFriend(currentUserId(request), name);
    }

    @GetMapping("/addFriend")
    public void addFriend(HttpServletRequest request, Integer friendId, String reason) {
        friendService.addFriend(currentUserId(request), friendId,
                reason == null ? "" : reason);
    }

    @GetMapping("/getFriendRequest")
    public List<FriendRequestResponse> getFriendRequest(HttpServletRequest request) {
        return friendService.getFriendRequest(currentUserId(request));
    }

    @GetMapping("/acceptFriend")
    public void acceptFriend(HttpServletRequest request, Integer friendId) {
        friendService.acceptFriend(currentUserId(request), friendId);
    }

    @GetMapping("/rejectFriend")
    public void rejectFriend(HttpServletRequest request, Integer friendId) {
        friendService.rejectFriend(currentUserId(request), friendId);
    }

}
