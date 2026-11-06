package com.yyx.ciyuantown.business.homeconfig.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("home_channel")
public class HomeChannel { @TableId(type=IdType.AUTO) private Long id; private String name; private Integer sortOrder; private Boolean enabled; private Boolean isDefault; private Boolean adminOnly; private Boolean showAllPosts; private Boolean allowPublish; private LocalDateTime createTime; private LocalDateTime updateTime; }
