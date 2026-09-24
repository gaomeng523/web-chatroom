package com.example.chatroom.pojo.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表实体。
 * 库中列名已统一为蛇形（message_id / from_id / session_id / content / post_time）。
 */
@Data
@TableName("message")
public class Message {

    /** @TableId(AUTO) 必须有，否则 insert 后 MP 不回填自增主键 */
    @TableId(type = IdType.AUTO)
    private Integer messageId;

    private Integer fromId;

    private Integer sessionId;

    private String content;

    /** 库里是 datetime（无小数秒），同一秒内的多条消息排序要靠 messageId 兜底 */
    private LocalDateTime postTime;

    /**
     * 是否已撤回：0 正常，1 已撤回。
     * 用软删除而不是物理 delete —— 撤回要在双方界面上留"XX 撤回了一条消息"的痕迹，
     * 而且超 2 分钟要能被拒（得查得到原发布时间），行删了这两件事都做不到。
     */
    private Integer revoked;

    /**
     * 1 文本 / 2 图片（取值见 Constant.MSG_TYPE_*）。
     * 图片消息把图片路径存在 content 里 —— 复用一个 content 列而不是给每种类型加一列，
     * 否则以后加语音、文件、位置，表会越来越宽。
     */
    private Integer type;
}
