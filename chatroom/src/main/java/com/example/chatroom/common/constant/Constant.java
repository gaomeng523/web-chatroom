package com.example.chatroom.common.constant;

/**
 * 全局常量。
 * JWT claim 的键名统一从这里取，避免生成 token 和解析 token 两边写不一致。
 */
public class Constant {

    /** 前端携带 token 的请求头名 */
    public static final String USER_TOKEN_HEADER = "User-Token";

    /** JWT 中存放用户 id 的 claim 键 */
    public static final String JWT_CLAIM_ID = "userId";

    /** JWT 中存放用户名的 claim 键 */
    public static final String JWT_CLAIM_NAME = "username";

    /** 登录校验通过后，放到 request 域中的当前用户 id */
    public static final String CURRENT_USER_ID = "currentUserId";

    /** 登录校验通过后，放到 request 域中的当前用户名 */
    public static final String CURRENT_USER_NAME = "currentUserName";

    /** WebSocket 握手路径。浏览器原生 WebSocket 不支持自定义请求头，鉴权只能走下面的 query 参数 */
    public static final String WS_MESSAGE_PATH = "/ws/message";

    /** WebSocket 握手时携带 token 的 query 参数名 */
    public static final String WS_TOKEN_PARAM = "token";

    /** WebSocket 推送类型：聊天消息 */
    public static final String WS_TYPE_MESSAGE = "message";

    /** WebSocket 推送类型：收到好友申请 */
    public static final String WS_TYPE_ADD_FRIEND_REQUEST = "addFriendRequest";

    /** WebSocket 推送类型：好友申请被通过 */
    public static final String WS_TYPE_ACCEPT_FRIEND = "acceptFriend";

    /** WebSocket 推送类型：某条消息被撤回 */
    public static final String WS_TYPE_REVOKE = "revoke";

    /** WebSocket 推送类型：处理失败，把原因回给发送者 */
    public static final String WS_TYPE_ERROR = "error";

    /**
     * WebSocket 推送类型：你被拉进了一个群。
     * <p>
     * 只推"被拉的人"，不推创建者自己 —— 创建者是自己点的建群，
     * 前端建群成功后自己刷列表就行，推给他反而会重复刷新。
     */
    public static final String WS_TYPE_GROUP_CREATED = "groupCreated";

    /**
     * WebSocket 指令类型：心跳探测（客户端 → 服务端）。
     * <p>
     * 浏览器原生 WebSocket 不暴露 ping API，拿不到底层 ping/pong 帧，所以在应用层自己造一对。
     * 它干两件事：
     * <ul>
     *   <li>给中间的反向代理持续喂数据 —— 很多代理 60s 无流量就直接掐连接，</li>
     *   <li>让客户端能发现"TCP 还连着、对面其实早不响应了"的<b>半开连接</b>
     *       （拔网线、笔记本睡眠、4G 切 WiFi 时 TCP 不会立刻报错，onclose 可能几分钟都不触发）。</li>
     * </ul>
     * 它没有业务含义：不落库、不广播、不校验会话。
     */
    public static final String WS_TYPE_PING = "ping";

    /** WebSocket 推送类型：心跳应答（服务端 → 客户端）。前端收到直接 return，不做任何处理 */
    public static final String WS_TYPE_PONG = "pong";

    /**
     * 连接"最后一次活跃时间"存在 {@code WebSocketSession.getAttributes()} 里用的 key。
     * 空闲回收（{@code OnlineUserManager.closeIdleSessions}）靠它判断哪条连接已经死了。
     */
    public static final String WS_SESSION_LAST_ACTIVE = "lastActiveAt";

    /**
     * 消息可撤回的时间窗口（分钟）。超过这个时间的消息不给撤。
     * <p>
     * ⚠️ 前端 client.js 里有一个同名的 REVOKE_WINDOW_MS，改这里时必须一起改。
     * 前端那个只用来决定"要不要显示撤回按钮"，真正说了算的是后端
     * {@code MessageMapper.revokeMessage} 里的 {@code post_time >= deadline}。
     */
    public static final int REVOKE_WINDOW_MINUTES = 2;

    /** 会话类型：单聊 */
    public static final int SESSION_TYPE_SINGLE = 1;

    /** 会话类型：群聊 */
    public static final int SESSION_TYPE_GROUP = 2;

    /** 消息类型：文本 */
    public static final int MSG_TYPE_TEXT = 1;

    /** 消息类型：图片（content 里存的是图片路径） */
    public static final int MSG_TYPE_IMAGE = 2;

    /** 上传文件的访问前缀，对应 UploadConfig 里的静态资源映射 */
    public static final String UPLOAD_URL_PREFIX = "/upload";

    /** 头像上传的子目录 */
    public static final String AVATAR_DIR = "avatar";

    /** 聊天图片上传的子目录 */
    public static final String CHAT_IMAGE_DIR = "chat";
}
