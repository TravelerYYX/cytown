package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
public record AppUserStatusParam(
    @NotNull(message="用户ID不能为空") @Positive(message="用户ID必须为正数") Long id,
    @NotNull(message="状态不能为空") @Min(value=0,message="状态仅允许0、1") @Max(value=1,message="状态仅允许0、1") Integer status) {}
