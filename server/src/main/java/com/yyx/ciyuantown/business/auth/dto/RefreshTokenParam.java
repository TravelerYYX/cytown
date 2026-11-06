package com.yyx.ciyuantown.business.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新 token 请求参数
 */
@Data
public class RefreshTokenParam {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
