package com.yyx.ciyuantown.common.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务响应码（与前端 .env 中约定对齐）
 *
 * <p>0000 成功；1000 业务失败；9999 token 过期（前端自动刷新）；8888 未登录（前端登出）</p>
 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    SUCCESS("0000", "ok"),

    FAIL("1000", "操作失败"),

    TOKEN_EXPIRED("9999", "登录已过期，请刷新"),

    UNAUTHORIZED("8888", "未登录或登录已失效");

    private final String code;

    private final String msg;
}
