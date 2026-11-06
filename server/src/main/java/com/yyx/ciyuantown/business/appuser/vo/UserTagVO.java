package com.yyx.ciyuantown.business.appuser.vo;
/** 用户已选标签视图（tagId 为 null 表示自定义标签）。 */
public record UserTagVO(Long tagId, String name, boolean custom) {}
