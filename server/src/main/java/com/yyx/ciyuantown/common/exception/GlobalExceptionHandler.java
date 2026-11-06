package com.yyx.ciyuantown.common.exception;

import com.yyx.ciyuantown.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有异常统一转换为 Result 结构返回
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return Result.failed("上传文件超过服务器大小限制，APK安装包最大500MB");
    }

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        return Result.of(e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return Result.failed(msg);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return Result.failed("请求方法错误：该接口不支持 " + e.getMethod() + " 请求，请使用 " + (e.getSupportedHttpMethods() != null ? e.getSupportedHttpMethods() : "POST"));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.failed("系统异常，请稍后重试");
    }
}
