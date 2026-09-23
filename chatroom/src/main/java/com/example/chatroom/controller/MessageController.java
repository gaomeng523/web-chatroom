package com.example.chatroom.controller;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.pojo.response.MessageResponse;
import com.example.chatroom.service.MessageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 查历史消息，对应 client.js：GET /message?sessionId=1
     */
    @GetMapping("/message")
    public List<MessageResponse> getHistoryMessage(HttpServletRequest request, Integer sessionId) {
        // 当前用户只从拦截器放进 request 域的属性里取，绝不从请求参数取，否则前端改个 id 就能冒充别人
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        return messageService.getHistoryMessage(userId, sessionId);
    }
}
