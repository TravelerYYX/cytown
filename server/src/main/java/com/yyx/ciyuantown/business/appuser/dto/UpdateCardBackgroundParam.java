package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotBlank;
public record UpdateCardBackgroundParam(@NotBlank(message="请选择卡片背景") String background) {}
