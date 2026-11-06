package com.yyx.ciyuantown.business.publish.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("publish_rule")
public class PublishRule {
 @TableId(type=IdType.INPUT) private String mode;
 private Integer titleMin; private Integer titleMax; private Integer contentMin; private Integer contentMax;
 private Integer imageMax; private Integer videoMax; private Integer totalMediaMax; private Integer maxVideoMb;
 private LocalDateTime updateTime;
}
