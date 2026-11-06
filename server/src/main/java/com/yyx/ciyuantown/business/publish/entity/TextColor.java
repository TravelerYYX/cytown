package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("text_color")
public class TextColor { @TableId(type=IdType.AUTO) private Long id; private String name; private String colorValue; private Integer sortOrder; private Boolean enabled; }
