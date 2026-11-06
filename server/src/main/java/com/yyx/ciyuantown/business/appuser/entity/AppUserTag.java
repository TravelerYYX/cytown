package com.yyx.ciyuantown.business.appuser.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** 用户标签关联实体（tagId 为 null 表示自定义标签，使用 customName）。 */
@Data
@TableName("app_user_tag")
public class AppUserTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long tagId;
    private String customName;
    private Integer sortOrder;
    private LocalDateTime createTime;
}
