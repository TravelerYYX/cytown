package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** Comment 持久化实体。 */
@Data
@TableName("`comment`")
public class Comment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long parentId;
    private Long replyToId;
    private String replyToNickname;
    private Long authorId;
    private String authorNickname;
    private Integer authorLevel;
    private String region;
    private String content;
    private String contentType;
    private Long stickerId;
    private String stickerUrl;
    private Boolean pinned;
    private Integer likeCount;
    private Integer dislikeCount;
    private Boolean deleted;
    private LocalDateTime deletedTime;
    private LocalDateTime createTime;
}
