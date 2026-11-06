package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("publish_emoji")
public class PublishEmoji { @TableId(type=IdType.AUTO) private Long id; private Long groupId; private String name; private String imageUrl; private Integer sortOrder; private Boolean enabled; }
