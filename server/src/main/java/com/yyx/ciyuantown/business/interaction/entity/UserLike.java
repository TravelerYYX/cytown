package com.yyx.ciyuantown.business.interaction.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("user_like")
public class UserLike {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long postId;
    private LocalDateTime createTime;
}
