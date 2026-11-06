package com.yyx.ciyuantown.business.mineentry;

import lombok.Data;

@Data
public class MineEntryParam {
    private Long id;
    private String title;
    private String description;
    private String iconUrl;
    private String targetType = "link";
    private String linkUrl;
    private String openMode = "inapp";
    private Boolean showTopbar = true;
    private Integer sortOrder = 0;
    private Boolean enabled = true;
}
