package com.yyx.ciyuantown.business.post.vo;
import lombok.Data;
import java.util.List;
@Data
public class PostListVO {
    private Boolean liked = false;
    private Boolean favorited = false;
    private Boolean following = false;
    private Boolean followedBy = false;
    private Integer favoriteCount;
    private String id;
    private String authorId;
    private String authorNickname;
    private Integer authorLevel;
    /** 作者头像相对路径（app_user.avatar），未设置或作者不存在时为 null。 */
    private String authorAvatar;
    private String authorAvatarFrame;
    private Long authorAvatarFrameExpiresAt;
    private String authorPublicUid;
    private String mode;
    private Long channelId;
    private String channelName;
    private String repostOfPostId;
    private RepostSummary repost;
    public record RepostSummary(String id,String authorNickname,String label,String coverUrl,boolean available) {}
    private String title;
    private String content;
    private String date;
    private String createTime;
    private Integer status;
    private boolean pinned;
    private Boolean homeNewsEnabled;
    private Boolean checkinRecommended;
    private Object contentBlocks;
    private Boolean allowDownload;
    private Boolean downloadWatermark;
    private Boolean original;
    private List<MediaItem> media;
    private List<String> tags;
    private Integer likeCount;
    private Integer commentCount;
    private Integer shareCount;
    public record MediaItem(Long id,Long assetId,String type, String layout, Double aspectRatio, Integer width, Integer height,
                            Long durationMs, String duration, String url, String coverUrl,String previewUrl,String originalUrl,String hlsUrl,Object qualities,String resourceVersion,String status,String errorMessage) {}
}
