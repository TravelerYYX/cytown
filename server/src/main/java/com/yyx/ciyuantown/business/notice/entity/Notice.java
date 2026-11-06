package com.yyx.ciyuantown.business.notice.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("notice")
public class Notice { @TableId(type=IdType.AUTO) private Long id; private Long categoryId; private String title; private String blocks; private Long postId; private Integer status; private String audienceType; private Boolean pushEnabled; private Long publicationId; private LocalDateTime publishTime; private LocalDateTime createTime; private LocalDateTime updateTime; }
