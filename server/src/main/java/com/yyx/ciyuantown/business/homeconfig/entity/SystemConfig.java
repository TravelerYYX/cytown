package com.yyx.ciyuantown.business.homeconfig.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("system_config")
public class SystemConfig { @TableId(type=IdType.INPUT) private String configKey; private String configValue; private String remark; private LocalDateTime updateTime; }
