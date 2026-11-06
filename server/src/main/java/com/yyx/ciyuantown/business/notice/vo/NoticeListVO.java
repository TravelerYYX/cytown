package com.yyx.ciyuantown.business.notice.vo;
import lombok.Data;
@Data public class NoticeListVO { private Long id; private Long categoryId; private String categoryName; private String title; private Integer status; private String publishTime; private Long publicationId; private Boolean unread; private String audienceType; private Boolean pushEnabled; private java.util.List<Long> recipientUserIds; private java.util.List<java.util.Map<String,Object>> pushTasks; private String createTime; }
