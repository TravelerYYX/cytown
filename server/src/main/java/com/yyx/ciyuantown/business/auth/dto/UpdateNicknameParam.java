package com.yyx.ciyuantown.business.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改昵称请求参数
 */
@Data
public class UpdateNicknameParam {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 20, message = "昵称长度需在 2-20 个字符之间")
    private String nickname;
}
