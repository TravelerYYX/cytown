package com.yyx.ciyuantown.business.appuser.dto;
import jakarta.validation.constraints.*;
public record AccountUpdateParam(@NotNull @Positive Long id, @NotBlank @Size(max=64) String account) {}
