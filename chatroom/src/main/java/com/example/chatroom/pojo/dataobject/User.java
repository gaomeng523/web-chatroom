package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class User {
    /**
     * 主键：必须标注 @TableId，MyBatis-Plus 才知道这是自增主键，
     * 否则 insert 之后不会把数据库生成的主键回填到对象里（注册接口会返回 userId:null）。
     * 列名不写死，交给 MP 按下划线规则推导为 user_id，与其他字段保持一致。
     */
    @TableId(type = IdType.AUTO)
    private Integer userId;
    private String userName;
    private String password;

    /**
     * 头像图片的相对路径，如 /upload/avatar/4_9f2c.png；NULL 表示没设置过、用首字母头像。
     * 存路径而不是 base64：base64 会让每行膨胀 33%，每次查 user 都得把图片一起拖出来，
     * 而且完全用不上浏览器缓存。
     */
    private String avatar;
}
