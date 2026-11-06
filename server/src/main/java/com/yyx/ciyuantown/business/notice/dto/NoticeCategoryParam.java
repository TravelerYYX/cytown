package com.yyx.ciyuantown.business.notice.dto;
import lombok.Data;
@Data public class NoticeCategoryParam { private Long id; private String name; private String iconUrl=""; private Integer sortOrder=0; private Boolean enabled=true; private String recipientPolicy="all"; private java.util.List<Long> recipientUserIds=java.util.List.of(); }
