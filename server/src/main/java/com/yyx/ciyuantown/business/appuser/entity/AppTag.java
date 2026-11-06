package com.yyx.ciyuantown.business.appuser.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** 用户标签库实体。 */
@Data
@TableName("app_tag")
public class AppTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private Integer source;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
