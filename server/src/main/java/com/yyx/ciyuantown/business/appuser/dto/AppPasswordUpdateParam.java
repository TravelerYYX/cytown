package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
/** 修改密码：需校验旧密码 */
public record AppPasswordUpdateParam(
    @NotBlank(message="请输入旧密码") String oldPassword,
    @NotBlank(message="请输入新密码") String newPassword) {}
