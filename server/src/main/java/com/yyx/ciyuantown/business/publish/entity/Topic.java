package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("topic")
public class Topic {
 @TableId(type=IdType.AUTO) private Long id; private String name; private String description; private String coverUrl;
 private Integer status; private Boolean recommended; private Boolean hot; private Boolean enabled; private Integer sortOrder;
 private Long creatorId; private Long viewCount; private LocalDateTime createTime; private LocalDateTime updateTime;
}
