package com.yyx.ciyuantown.business.appauth.dto;
import jakarta.validation.constraints.*;
public record SendCodeParam(@NotBlank(message="请输入QQ号") @Pattern(regexp="[0-9]{5,11}",message="QQ号格式不正确") String qqNumber) {}
