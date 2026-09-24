package com.example.chatroom.controller;

import com.example.chatroom.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 头像读取接口 GET /avatar/{userId}
 * <p>
 * 为什么单独做一个"按 userId 拿头像"的接口，而不是在 /userInfo、/friendList、
 * 会话列表、消息推送里各带一个 avatarUrl 字段：
 * <ul>
 *   <li>那些接口的 SQL 全都不用改（头像 URL 在前端就能拼出来）</li>
 *   <li>前端随便什么位置只要知道 userId 就能显示头像，不用等接口返回</li>
 * </ul>
 * <p>
 * ⚠️ 这个接口必须在 WebConfig 里放行：{@code <img src>} 发出去的是浏览器原生请求，
 * 带不了 User-Token 请求头，会被登录拦截器判 401。
 * 头像本身也不算敏感信息（图个方便，真要严格就该走签名 URL）。
 */
@Slf4j
@RestController
public class AvatarController {

    private final UserService userService;

    public AvatarController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<Resource> getAvatar(@PathVariable Integer userId) {
        Resource resource = userService.loadAvatar(userId);
        if (resource == null) {
            // 前端 <img onerror> 会收到这个 404，回退成首字母色块头像
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .contentType(mediaType)
                // 换头像后 URL 还是 /avatar/4，不禁缓存的话浏览器会一直显示旧图。
                // no-cache 不是"不缓存"，而是"每次先跟服务端确认"，不新鲜才重新下载。
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }
}
