package com.yyx.ciyuantown.business.post.dto;
import jakarta.validation.constraints.*;
public record PostStatusParam(
    @NotNull(message="帖子ID不能为空") @Positive(message="帖子ID必须为正数") Long id,
    @NotNull(message="状态不能为空") @Min(value=0,message="状态仅允许0、1、2") @Max(value=2,message="状态仅允许0、1、2") Integer status) {}
