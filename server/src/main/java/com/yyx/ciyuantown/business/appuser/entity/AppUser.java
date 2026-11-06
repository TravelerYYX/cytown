package com.yyx.ciyuantown.business.appuser.entity;

import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long publicUid;
    private String qqNumber;
    /** 普通用户自定义登录账号，NULL 时仍用 QQ 号 */
    private String username;
    /** 官方社区身份的固定关联 */
    private Long officialAdminId;
    private String email;
    /** 登录密码(BCrypt)，NULL=未设置密码，仅验证码登录 */
    private String password;
    private String nickname;
    private String avatar;
    private String avatarFrame;
    private Long avatarFrameExpiresAt;
    private BigDecimal coinBalance;
    private String signature;
    private Integer gender;
    private String cardBackground;
    private Integer level;
    private Long experience;
    private Integer status;
    private Integer tokenVersion;
    /** 独立于禁用状态；历史内容仍通过原用户 ID 关联。 */
    private Boolean deleted;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    public boolean hasBeenDeleted() { return Boolean.TRUE.equals(deleted); }
    private LocalDateTime lastLoginTime;
    private Long activeDays;
    private LocalDate lastActiveDate;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;
}
