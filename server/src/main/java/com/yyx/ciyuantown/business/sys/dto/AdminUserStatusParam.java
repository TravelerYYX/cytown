package com.yyx.ciyuantown.business.sys.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用/禁用官方账号参数
 */
public record AdminUserStatusParam(
        @NotNull(message = "账号ID不能为空") Long id,
        @NotNull(message = "状态不能为空") @Min(value = 0, message = "状态仅允许0、1") @Max(value = 1, message = "状态仅允许0、1") Integer status) {
}
