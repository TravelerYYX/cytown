package com.yyx.ciyuantown.business.appuser.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** 系统头像库实体。 */
@Data
@TableName("app_system_avatar")
public class AppSystemAvatar {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String url;
    private Integer type;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
