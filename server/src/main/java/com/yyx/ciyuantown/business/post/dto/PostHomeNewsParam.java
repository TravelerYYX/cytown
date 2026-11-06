package com.yyx.ciyuantown.business.post.dto;
import jakarta.validation.constraints.*;
public record PostHomeNewsParam(
    @NotNull(message="帖子ID不能为空") @Positive(message="帖子ID必须为正数") Long id,
    @NotNull(message="首页资讯轮播设置不能为空") Boolean homeNewsEnabled) {}
