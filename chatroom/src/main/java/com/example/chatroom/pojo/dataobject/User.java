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
}
