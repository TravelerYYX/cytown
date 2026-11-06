package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** PostTag 持久化实体。 */
@Data
@TableName("post_tag")
public class PostTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private String name;
    private Integer sortOrder;
}
