package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotNull;
public record SelectSystemAvatarParam(@NotNull(message="请选择头像") Long avatarId) {}
