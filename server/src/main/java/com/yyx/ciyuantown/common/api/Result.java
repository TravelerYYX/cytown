package com.yyx.ciyuantown.common.api;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结构：{ code, msg, data }
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String code;

    private String msg;

    private T data;

    public static <T> Result<T> success(T data) {
        return of(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> success() {
        return of(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    public static <T> Result<T> failed(String msg) {
        return of(ResultCode.FAIL.getCode(), msg, null);
    }

    public static <T> Result<T> failed(ResultCode resultCode) {
        return of(resultCode.getCode(), resultCode.getMsg(), null);
    }

    public static <T> Result<T> of(String code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
}
