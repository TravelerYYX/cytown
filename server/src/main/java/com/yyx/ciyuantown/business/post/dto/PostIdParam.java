package com.yyx.ciyuantown.business.post.dto;
import jakarta.validation.constraints.*;
public record PostIdParam(@NotNull(message="帖子ID不能为空") @Positive(message="帖子ID必须为正数") Long id) {}
