package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("emoji_group")
public class EmojiGroup { @TableId(type=IdType.AUTO) private Long id; private String name; private Integer sortOrder; private Boolean enabled; }
