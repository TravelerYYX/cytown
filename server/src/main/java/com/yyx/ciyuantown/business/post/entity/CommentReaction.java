package com.yyx.ciyuantown.business.post.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("comment_reaction")
public class CommentReaction {
    @TableId(type = IdType.AUTO) private Long id;
    private Long commentId;
    private Long userId;
    private String reaction;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
