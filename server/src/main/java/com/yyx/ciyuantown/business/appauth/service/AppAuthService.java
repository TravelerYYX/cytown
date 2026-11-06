package com.yyx.ciyuantown.business.appauth.service;
import com.yyx.ciyuantown.business.appauth.vo.AppLoginResult;
public interface AppAuthService {
    AppLoginResult login(String qqNumber, String code);
    /**
     * 账号 + 密码登录。
     * <p>QQ 号（5-11 位纯数字）登录 app_user，需已设置密码，未设置时提示走验证码登录；
     * 官方账号（cyt####）登录 admin_user，密码以管理后台为准，首次登录自动建立绑定的社区身份。
     */
    AppLoginResult loginByPassword(String account, String password);
    AppLoginResult refreshToken(String refreshToken);
    void logout(Long userId,String accessToken);
}
