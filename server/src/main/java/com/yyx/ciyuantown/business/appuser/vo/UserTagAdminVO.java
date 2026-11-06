package com.yyx.ciyuantown.business.appuser.vo;
import com.yyx.ciyuantown.business.appuser.entity.AppTag;
import java.time.LocalDateTime;
/** 管理端用户标签视图。 */
public record UserTagAdminVO(Long id, String name, String category, Integer source, Integer status, Integer sort, LocalDateTime createTime) {
    public static UserTagAdminVO from(AppTag t) {
        return new UserTagAdminVO(t.getId(),t.getName(),t.getCategory(),t.getSource(),t.getStatus(),t.getSort(),t.getCreateTime());
    }
}
