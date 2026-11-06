package com.yyx.ciyuantown.business.appuser.vo;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import java.time.format.DateTimeFormatter;
public record AppUserManageVO(String id, String publicUid, String qqNumber, String nickname, String avatar, Integer status,
                              String lastLoginTime, String createTime, String account, String officialAdminId) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static AppUserManageVO from(AppUser u) {
        return from(u,u.getUsername()==null?u.getQqNumber():u.getUsername());
    }
    public static AppUserManageVO from(AppUser u, String account) {
        return new AppUserManageVO(u.getId().toString(), u.getPublicUid()==null?null:u.getPublicUid().toString(), u.getQqNumber(), u.getNickname(), u.getAvatar(), u.getStatus(),
            u.getLastLoginTime() == null ? null : u.getLastLoginTime().format(TIME), u.getCreateTime()==null?null:u.getCreateTime().format(TIME), account, u.getOfficialAdminId()==null?null:u.getOfficialAdminId().toString());
    }
}
