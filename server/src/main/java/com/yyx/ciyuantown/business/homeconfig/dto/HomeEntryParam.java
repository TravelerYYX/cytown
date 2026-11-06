package com.yyx.ciyuantown.business.homeconfig.dto;
import lombok.Data;
@Data public class HomeEntryParam { private Long id; private String name; private String iconUrl; private String targetType="link"; private String linkUrl; private String openMode="inapp"; private Boolean showTopbar=true; private Integer sortOrder=0; private Boolean enabled=true; }
