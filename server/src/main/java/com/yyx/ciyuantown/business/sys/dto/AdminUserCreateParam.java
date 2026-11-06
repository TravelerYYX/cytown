package com.yyx.ciyuantown.business.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建官方账号参数：完整自定义账号，统一登记保证唯一性
 */
public record AdminUserCreateParam(
        @NotBlank(message = "请输入账号") @Size(max=64, message="账号过长") String account,
        @NotBlank(message = "请输入初始密码") String password,
        @Size(max = 20, message = "昵称不能超过20字") String nickname) {
}
