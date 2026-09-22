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
}
