package com.example.chatroom.pojo.request;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * add_friend_request 表的实体，只给 MyBatis-Plus 用（insert / delete / 条件构造器）。
 * <p>
 * 列名推导：fromUserId → from_user_id、toUserId → to_user_id，
 * 靠 application.yml 的 map-underscore-to-camel-case 自动完成，无需 @TableField。
 * <p>
 * 两条纪律：
 * 1. 不要加 type 字段。type 是 WebSocket 消息类型，属于另一个 DTO；
 *    混在这里会让 MP 推导出不存在的 type 列，查询时报 Unknown column。
 * 2. 不要挂 @JsonInclude(NON_NULL)。这个是表实体，不是接口返回体；
 *    查询结果里 join 出来的字段（如 fromUserName）会被 NON_NULL 一起干掉。
 *    要返回给前端的结构请用 FriendRequestResponse。
 */
@Data
@TableName("add_friend_request")
public class AddFriendRequest {
    private Integer fromUserId;
    private Integer toUserId;
    private String reason;
}
