package com.yyx.ciyuantown.business.appauth.annotation;
import java.lang.annotation.*;
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AppLoginRequired {}
