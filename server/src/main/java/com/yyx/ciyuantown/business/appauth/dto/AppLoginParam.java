package com.yyx.ciyuantown.business.appauth.dto;
import jakarta.validation.constraints.*;
public record AppLoginParam(
    @NotBlank(message="请输入QQ号") @Pattern(regexp="[0-9]{5,11}",message="QQ号格式不正确") String qqNumber,
    @NotBlank(message="请输入验证码") @Pattern(regexp="[0-9]{6}",message="验证码错误或已过期") String code) {}
