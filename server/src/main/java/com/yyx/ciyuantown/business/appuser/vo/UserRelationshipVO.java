package com.yyx.ciyuantown.business.appuser.vo;

public record UserRelationshipVO(String userId, String publicUid, String nickname, String avatar,
                                 Integer level, String signature, boolean following, boolean followedBy, String avatarFrame, Long avatarFrameExpiresAt) {}
