package com.yyx.ciyuantown.business.post.dto;
import java.util.List;
public record CommentCreateParam(Long parentId, Long replyToId, String content, List<Long> assetIds, Long emojiId, String region) {}
