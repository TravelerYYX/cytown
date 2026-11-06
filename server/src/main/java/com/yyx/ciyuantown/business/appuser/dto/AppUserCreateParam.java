package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
/** 管理端新建 APP 用户：密码可选，留空=仅验证码登录 */
public record AppUserCreateParam(
    @NotBlank(message="请输入QQ号") @Pattern(regexp="[0-9]{5,11}",message="QQ号格式不正确") String qqNumber,
    @Size(max=20,message="昵称不能超过20字") String nickname,
    String password,
    @Size(max=64,message="账号过长") String account) {}
