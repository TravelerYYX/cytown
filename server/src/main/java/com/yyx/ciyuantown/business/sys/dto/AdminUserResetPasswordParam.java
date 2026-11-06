package com.yyx.ciyuantown.business.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 重置官方账号密码参数：新密码由超管输入；成功后该账号旧 token 全部失效
 */
public record AdminUserResetPasswordParam(
        @NotNull(message = "账号ID不能为空") Long id,
        @NotBlank(message = "请输入新密码") String password) {
}
