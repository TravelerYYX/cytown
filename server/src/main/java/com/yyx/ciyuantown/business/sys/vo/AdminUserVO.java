package com.yyx.ciyuantown.business.sys.vo;

import com.yyx.ciyuantown.business.sys.entity.AdminUser;

import java.time.format.DateTimeFormatter;

/**
 * 官方账号视图：严禁加入 password 字段
 */
public record AdminUserVO(String id, String username, String nickname, Integer status, Integer userType,
                          String createdBy, String createTime) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static AdminUserVO from(AdminUser u) {
        return new AdminUserVO(u.getId().toString(), u.getUsername(), u.getNickname(),
                u.getStatus() == null ? 0 : u.getStatus(),
                u.getUserType() == null ? 0 : u.getUserType(),
                u.getCreatedBy() == null ? null : u.getCreatedBy().toString(),
                u.getCreateTime() == null ? null : u.getCreateTime().format(TIME));
    }
}
