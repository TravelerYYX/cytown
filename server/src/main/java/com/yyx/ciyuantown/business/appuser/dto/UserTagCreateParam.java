package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
/** 新增用户标签库标签入参。 */
public record UserTagCreateParam(
    @NotBlank(message="请输入标签名称") String name,
    @NotBlank(message="请输入标签分类") String category,
    Integer sort, Integer status) {}
