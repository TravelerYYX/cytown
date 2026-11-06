package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("divider_style")
public class DividerStyle { @TableId(type=IdType.AUTO) private Long id; private String name; private String previewText; private Integer sortOrder; private Boolean enabled; }
