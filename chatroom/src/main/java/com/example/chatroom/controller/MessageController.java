package com.example.chatroom.controller;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.pojo.response.MessageResponse;
import com.example.chatroom.pojo.response.MessageSearchResponse;
import com.example.chatroom.service.MessageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 撤回消息，对应 client.js：GET /revokeMessage?messageId=123
     * <p>
     * 撤回走 HTTP 而不是 WebSocket：它是低频写操作，需要能返回明确的失败原因
     * （超过 2 分钟、不是你的消息……），走 HTTP 的 400 + message 最顺。
     * 撤回成功后的"通知"才走 WebSocket 广播。
     */
    @GetMapping("/revokeMessage")
    public void revokeMessage(HttpServletRequest request, Integer messageId) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        messageService.revokeMessage(userId, messageId);
    }

    /**
     * 搜索自己所有会话里的消息，对应 client.js：GET /searchMessage?keyword=xxx
     */
    @GetMapping("/searchMessage")
    public List<MessageSearchResponse> searchMessage(HttpServletRequest request, String keyword) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        return messageService.searchMessage(userId, keyword);
    }

    /**
     * 上传聊天图片，返回 {"url": "/upload/chat/4_9f2c1a3b5d7e.png"}。
     * <p>
     * 上传和"发送消息"是分开的两步：这里只把文件存下来，
     * 前端拿到 url 之后再通过 WebSocket 发一条 contentType=2 的消息。
     * 这么做的好处是上传失败/被拒时前端能立刻知道，不用等 WS 那条链路的报错。
     */
    @PostMapping("/message/image")
    public Map<String, String> uploadImage(HttpServletRequest request,
                                           @RequestParam("file") MultipartFile file) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        Map<String, String> result = new HashMap<>();
        result.put("url", messageService.uploadImage(userId, file));
        return result;
    }
}
