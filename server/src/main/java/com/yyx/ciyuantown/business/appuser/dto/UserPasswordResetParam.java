package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
public record UserPasswordResetParam(@NotNull @Positive Long id, @NotBlank String password) {}
