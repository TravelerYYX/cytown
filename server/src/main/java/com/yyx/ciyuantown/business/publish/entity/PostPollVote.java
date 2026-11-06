package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("post_poll_vote")
public class PostPollVote { @TableId(type=IdType.AUTO) private Long id; private Long pollId; private Long optionId; private Long userId; private LocalDateTime createTime; }
