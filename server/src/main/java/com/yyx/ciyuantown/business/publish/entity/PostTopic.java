package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("post_topic")
public class PostTopic { private Long postId; private Long topicId; private Integer sortOrder; }
