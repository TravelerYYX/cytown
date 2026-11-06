package com.yyx.ciyuantown.business.interaction.vo;
public record InteractionResultVO(boolean active, int count, Boolean followedBy) {
    public InteractionResultVO(boolean active, int count) { this(active, count, null); }
}
