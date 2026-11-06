package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotNull;
/** 修改用户标签库标签入参（字段为 null 表示不修改）。 */
public record UserTagUpdateParam(
    @NotNull(message="请选择标签") Long id,
    String name, String category, Integer sort, Integer status) {}
