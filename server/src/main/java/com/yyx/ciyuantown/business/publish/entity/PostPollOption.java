package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("post_poll_option")
public class PostPollOption { @TableId(type=IdType.AUTO) private Long id; private Long pollId; private String optionText; private Integer voteCount; private Integer sortOrder; }
