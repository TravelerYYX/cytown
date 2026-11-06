package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotNull;
/** 管理系统头像入参（字段为 null 表示不修改）。 */
public record SystemAvatarUpdateParam(
    @NotNull(message="请选择头像") Long id,
    Integer type, Integer sort, Integer status) {}
