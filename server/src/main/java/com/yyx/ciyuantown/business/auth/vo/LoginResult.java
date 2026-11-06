package com.yyx.ciyuantown.business.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录/刷新 token 返回
 */
@Data
@AllArgsConstructor
public class LoginResult {

    private String token;

    private String refreshToken;
}
