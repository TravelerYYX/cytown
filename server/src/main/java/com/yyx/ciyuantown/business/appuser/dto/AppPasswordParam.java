package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
/** 首次设置密码 */
public record AppPasswordParam(@NotBlank(message="请输入密码") String password) {}
