package com.yyx.ciyuantown.business.post.vo;
import lombok.Data;
import java.util.List;
@Data
public class CommentVO {
    private String id;
    private String postId;
    private String parentId;
    private String replyToId;
    private String replyToNickname;
    private String authorId;
    private String authorNickname;
    private Integer authorLevel;
    private String authorAvatar;
    private String authorAvatarFrame;
    private Long authorAvatarFrameExpiresAt;
    private String region;
    private String content;
    private String contentType;
    private Long stickerId;
    private String stickerUrl;
    private Boolean pinned;
    private Boolean deleted;
    private Boolean contextOnly;
    private Boolean canDelete;
    private Integer likeCount;
    private String viewerReaction;
    private String createTime;
    private Integer replyCount;
    private List<Media> media = List.of();
    private List<CommentVO> replies = List.of();
    public record Media(Long id,Long assetId,String url,Integer width,Integer height,Integer sortOrder) {}
}
