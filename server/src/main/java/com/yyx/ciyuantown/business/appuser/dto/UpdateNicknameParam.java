package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotBlank;
public record UpdateNicknameParam(@NotBlank(message="请输入昵称") String nickname) {}
