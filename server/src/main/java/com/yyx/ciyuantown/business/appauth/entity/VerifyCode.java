package com.yyx.ciyuantown.business.appauth.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("verify_code")
public class VerifyCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String code;
    private LocalDateTime expireTime;
    private Boolean used;
    private LocalDateTime createTime;
}
