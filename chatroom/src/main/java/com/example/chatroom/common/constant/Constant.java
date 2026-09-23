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

    /** WebSocket 推送类型：处理失败，把原因回给发送者 */
    public static final String WS_TYPE_ERROR = "error";
}
