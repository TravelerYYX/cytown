package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** Post 持久化实体。 */
@Data
@TableName("`post`")
public class Post {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private String authorNickname;
    private Integer authorLevel;
    private String authorBio;
    private String mode;
    private Long channelId;
    private Long repostOfPostId;
    private String title;
    private String content;
    private String contentBlocks;
    private Integer status;
    private LocalDateTime profilePinnedAt;
    private Boolean homeNewsEnabled;
    private Boolean checkinRecommended;
    private String collection;
    private String permission;
    private Boolean allowDownload;
    private Boolean downloadWatermark;
    private Boolean original;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer commentCount;
    private Integer shareCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
