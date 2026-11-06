package com.yyx.ciyuantown.business.notice.dto;
import lombok.Data;
@Data public class NoticeParam { private Long id; private Long categoryId; private String title; private String blocks="[]"; private Long postId; private Integer status=0; private String audienceType="all"; private java.util.List<Long> recipientUserIds=java.util.List.of(); private Boolean pushEnabled=false; }
