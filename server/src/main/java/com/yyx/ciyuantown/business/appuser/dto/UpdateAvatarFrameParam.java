package com.yyx.ciyuantown.business.appuser.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UpdateAvatarFrameParam(
    @NotNull(message="请选择头像框")
    @Pattern(regexp="^(|star-sea|sakura-mist)$", message="头像框不存在") String frameId) {}
