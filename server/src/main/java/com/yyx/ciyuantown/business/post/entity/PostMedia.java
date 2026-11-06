package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** PostMedia 持久化实体。 */
@Data
@TableName("post_media")
public class PostMedia {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long assetId;
    private String type;
    private String layout;
    private Double aspectRatio;
    private Integer width;
    private Integer height;
    private String duration;
    private Long durationMs;
    private String url;
    private String coverUrl;
    private Long coverTimeMs;
    private String cropJson;
    private Integer sortOrder;
}
