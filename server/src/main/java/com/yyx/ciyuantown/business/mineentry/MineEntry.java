package com.yyx.ciyuantown.business.mineentry;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mine_entry")
public class MineEntry {
    @TableId(type = IdType.AUTO) private Long id;
    private String title;
    private String description;
    private String iconUrl;
    private String targetType;
    private String linkUrl;
    private String openMode;
    private Boolean showTopbar;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
