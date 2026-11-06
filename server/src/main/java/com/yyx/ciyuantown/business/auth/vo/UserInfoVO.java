package com.yyx.ciyuantown.business.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户信息返回（对齐前端 Api.Auth.UserInfo）
 */
@Data
public class UserInfoVO {

    /** 用户 id（前端约定为字符串） */
    private String userId;

    private String userName;

    /** 昵称（无昵称时前端回退显示用户名） */
    private String nickName;

    /** 头像相对路径，如 /uploads/avatar/xxx.png */
    private String avatar;

    private List<String> roles;

    private List<String> buttons;

    /** 账号类型：0超级管理员 1官方只读账号（前端据此显示只读标识、隐藏操作按钮） */
    private Integer userType;
}
