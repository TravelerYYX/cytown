package com.yyx.ciyuantown.business.notice.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("notice_category")
public class NoticeCategory { @TableId(type=IdType.AUTO) private Long id; private String name; private String iconUrl; private Integer sortOrder; private Boolean enabled; private String recipientPolicy="all"; @TableField(exist=false) private java.util.List<Long> recipientUserIds=java.util.List.of(); private LocalDateTime createTime; private LocalDateTime updateTime; }
