package com.yyx.ciyuantown.business.appauth.dto;
import jakarta.validation.constraints.*;
/**
 * 密码登录参数。
 * <p>{@code qqNumber} 为兼容既有 APP 契约保留的字段名，实际承载「账号」：
 * 自定义账号、未改名的 QQ 登录账号或官方账号，统一从 login_account 登记解析。
 * <p>账号**不做格式校验**（只限非空与长度上限）：能否登录一律由查库结果决定，
 * 避免「带字母的官方账号被格式校验挡下」这类问题再次出现。
 */
public record AppPasswordLoginParam(
    @NotBlank(message="请输入账号") @Size(max=64,message="账号过长") String qqNumber,
    @NotBlank(message="请输入密码") String password) {}
