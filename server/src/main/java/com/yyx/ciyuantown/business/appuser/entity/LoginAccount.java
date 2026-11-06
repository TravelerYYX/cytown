package com.yyx.ciyuantown.business.appuser.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 全局登录名登记；官方社区身份不重复登记。 */
@Data
@TableName("login_account")
public class LoginAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String account;
    /** 0 = app_user，1 = admin_user */
    private Integer ownerType;
    private Long ownerId;
}
