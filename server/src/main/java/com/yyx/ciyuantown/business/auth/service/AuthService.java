package com.yyx.ciyuantown.business.auth.service;

import com.yyx.ciyuantown.business.auth.vo.LoginResult;
import com.yyx.ciyuantown.business.auth.vo.UserInfoVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 认证服务
 */
public interface AuthService {

    /** 登录：校验用户名密码，签发 token 对 */
    LoginResult login(String userName, String password);

    /** 刷新 token：校验 refreshToken，签发新 token 对 */
    LoginResult refreshToken(String refreshToken);

    /** 获取用户信息（角色、按钮权限） */
    UserInfoVO getUserInfo(Long userId);

    /** 修改用户名 */
    void updateUserName(Long userId, String newUserName);

    /** 修改昵称 */
    void updateNickname(Long userId, String nickname);

    /** 上传头像：保存文件并更新用户 avatar 字段，返回头像相对路径 */
    String updateAvatar(Long userId, MultipartFile file);

    /** 修改密码 */
    void updatePassword(Long userId, String oldPassword, String newPassword);
}
