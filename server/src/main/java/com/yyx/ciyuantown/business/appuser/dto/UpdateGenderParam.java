package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.NotNull;
public record UpdateGenderParam(@NotNull(message="请选择性别") Integer gender) {}
