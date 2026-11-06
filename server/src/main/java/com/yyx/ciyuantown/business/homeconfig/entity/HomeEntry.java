package com.yyx.ciyuantown.business.homeconfig.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("home_entry")
public class HomeEntry { @TableId(type=IdType.AUTO) private Long id; private String name; private String iconUrl; private String targetType; private String linkUrl; private String openMode; private Boolean showTopbar; private Integer sortOrder; private Boolean enabled; private LocalDateTime createTime; private LocalDateTime updateTime; }
