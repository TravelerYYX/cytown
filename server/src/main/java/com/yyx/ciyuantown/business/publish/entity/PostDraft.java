package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("post_draft")
public class PostDraft {
 @TableId(type=IdType.AUTO) private Long id; private Long authorId; private String mode; private String title; private String summary;
 private String payload; private Long revision; private String coverUrl; private LocalDateTime createTime; private LocalDateTime updateTime;
}
