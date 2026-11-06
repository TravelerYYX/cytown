package com.yyx.ciyuantown.business.homeconfig.dto;

public record HeaderEntryParam(String name, String iconUrl, String targetType, String linkUrl,
                               String openMode, Boolean showTopbar, Boolean enabled) {}
