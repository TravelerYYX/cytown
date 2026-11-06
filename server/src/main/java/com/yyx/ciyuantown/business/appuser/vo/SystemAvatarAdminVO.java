package com.yyx.ciyuantown.business.appuser.vo;
import com.yyx.ciyuantown.business.appuser.entity.AppSystemAvatar;
import java.time.LocalDateTime;
/** 管理端系统头像视图。 */
public record SystemAvatarAdminVO(Long id, String url, Integer type, Integer sort, Integer status, LocalDateTime createTime) {
    public static SystemAvatarAdminVO from(AppSystemAvatar a) {
        return new SystemAvatarAdminVO(a.getId(),a.getUrl(),a.getType(),a.getSort(),a.getStatus(),a.getCreateTime());
    }
}
