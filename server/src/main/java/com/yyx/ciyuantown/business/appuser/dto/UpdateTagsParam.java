package com.yyx.ciyuantown.business.appuser.dto;
import java.util.List;
/** 用户标签整体覆盖提交（tagId 与 customName 二选一）。 */
public record UpdateTagsParam(List<TagItem> items) {
    public record TagItem(Long tagId, String customName) {}
}
