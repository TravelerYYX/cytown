package com.yyx.ciyuantown.business.appauth.vo;
import com.yyx.ciyuantown.business.appuser.vo.AppUserInfoVO;
public record AppLoginResult(String token, String refreshToken, AppUserInfoVO userInfo) {}
