package com.yyx.ciyuantown.business.appuser.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UserDeleteParam(
    @NotNull(message = "用户ID不能为空") @Positive(message = "用户ID必须为正数") Long id
) {}
