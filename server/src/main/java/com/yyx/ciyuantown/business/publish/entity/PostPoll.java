package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("post_poll")
public class PostPoll { @TableId(type=IdType.AUTO) private Long id; private Long postId; private String question; private LocalDateTime endsAt; private Integer totalVotes; }
