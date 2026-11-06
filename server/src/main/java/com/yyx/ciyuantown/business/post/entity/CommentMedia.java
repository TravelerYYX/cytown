package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("comment_media")
public class CommentMedia {
    @TableId(type = IdType.AUTO) private Long id;
    private Long commentId;
    private Long assetId;
    private String url;
    private Integer width;
    private Integer height;
    private Integer sortOrder;
}
