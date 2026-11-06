package com.yyx.ciyuantown.business.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台管理员
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    /** 账号类型：0超级管理员 1官方只读账号 */
    private Integer userType;

    /** 状态：0正常 1禁用 */
    private Integer status;

    /** token版本，禁用/重置密码时递增（踢下线） */
    private Integer tokenVersion;

    private Boolean deleted;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    public boolean hasBeenDeleted() { return Boolean.TRUE.equals(deleted); }

    /** 创建人（超管id），官方账号使用 */
    private Long createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
