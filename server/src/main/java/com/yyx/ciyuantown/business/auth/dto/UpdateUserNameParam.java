package com.yyx.ciyuantown.business.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改用户名请求参数
 */
@Data
public class UpdateUserNameParam {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 50, message = "用户名长度需在 2-50 个字符之间")
    private String userName;
}
