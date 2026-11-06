package com.yyx.ciyuantown.common.exception;

import com.yyx.ciyuantown.common.api.ResultCode;
import lombok.Getter;

/**
 * 业务异常：默认业务码 1000，也可指定 ResultCode（如 8888 触发前端登出）
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String msg) {
        super(msg);
        this.code = ResultCode.FAIL.getCode();
    }

    public BizException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }
}
