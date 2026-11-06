package com.yyx.ciyuantown.business.appuser.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
/** 系统头像分组视图：dynamic 动态头像，static 静态头像。 */
public record SystemAvatarGroupVO(List<SystemAvatarItemVO> dynamic,
                                  @JsonProperty("static") List<SystemAvatarItemVO> staticList) {
    public record SystemAvatarItemVO(Long id, String url) {}
}
