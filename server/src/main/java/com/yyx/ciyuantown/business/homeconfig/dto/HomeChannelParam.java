package com.yyx.ciyuantown.business.homeconfig.dto;
import lombok.Data;
@Data public class HomeChannelParam { private Long id; private String name; private Integer sortOrder=0; private Boolean enabled=true; private Boolean isDefault=false; private Boolean adminOnly; private Boolean showAllPosts; private Boolean allowPublish; }
