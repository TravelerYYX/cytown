package com.yyx.ciyuantown.common.util;

import com.yyx.ciyuantown.common.exception.BizException;

/**
 * 密码强度校验：管理端创建/重置官方账号密码与 APP 端设置/修改密码共用同一规则
 */
public final class Passwords {

    private Passwords() {
    }

    /**
     * 校验密码强度：8-20 位、不能包含空格（允许纯数字）。不合格抛 BizException，前端按提示文案展示。
     */
    public static void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 20) {
            throw new BizException("密码需为8-20位");
        }
        if (password.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF)) {
            throw new BizException("密码不能包含空格");
        }
    }
}
