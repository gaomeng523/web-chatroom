package com.example.chatroom.controller;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.pojo.response.SessionCreateResponse;
import com.example.chatroom.pojo.response.SessionListResponse;
import com.example.chatroom.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 当前用户只从拦截器放的 request 域取，绝不从请求参数取 */
    private Integer currentUserId(HttpServletRequest request) {
        return (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
    }

    @GetMapping("/sessionList")
    public List<SessionListResponse> getSessionList(HttpServletRequest request) {
        return sessionService.getSessionList(currentUserId(request));
    }

    /** 注意：POST 但参数挂 URL query，不是 body，所以别写 @RequestBody */
    @PostMapping("/session")
    public SessionCreateResponse createSession(HttpServletRequest request, Integer toUserId) {
        return sessionService.createSession(currentUserId(request), toUserId);
    }

    /**
     * 标记会话已读（点开会话时前端调一次），成功后清掉未读红点。
     * 写操作返回 void，和好友模块的 /acceptFriend 保持一致。
     */
    @GetMapping("/sessionRead")
    public void markSessionRead(HttpServletRequest request, Integer sessionId) {
        sessionService.markSessionRead(currentUserId(request), sessionId);
    }

    // ==================== 群聊 ====================
    // 和好友模块一样，全部平铺无前缀，且写操作走 POST 但参数挂 URL query。
    // 唯一例外是 /group 的 memberIds —— 它是个数组，query 里用 memberIds=4&memberIds=5
    // 这种重复参数名，Spring 直接能绑到 List<Integer>，不用 @RequestBody。

    /**
     * 建群。参数：name=群名&amp;memberIds=4&amp;memberIds=5
     *
     * @param memberIds 只放"别人"，创建者由后端从 token 里取、自动入群，
     *                  前端就算把自己的 id 传进来也会被 Service 去重掉
     */
    @PostMapping("/group")
    public SessionCreateResponse createGroup(HttpServletRequest request,
                                             String name,
                                             @RequestParam(required = false) List<Integer> memberIds) {
        return sessionService.createGroup(currentUserId(request), name, memberIds);
    }

    /** 往群里加人。参数：sessionId=3&amp;newMemberId=5 */
    @PostMapping("/groupMember")
    public void addGroupMember(HttpServletRequest request, Integer sessionId, Integer newMemberId) {
        sessionService.addGroupMember(currentUserId(request), sessionId, newMemberId);
    }

    /** 退群。参数：sessionId=3 */
    @PostMapping("/quitGroup")
    public void quitGroup(HttpServletRequest request, Integer sessionId) {
        sessionService.quitGroup(currentUserId(request), sessionId);
    }
}
