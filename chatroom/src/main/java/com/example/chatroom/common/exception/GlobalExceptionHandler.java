package com.example.chatroom.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 静态资源 / 接口不存在，属正常 404，只打一行日志、不打堆栈 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在：{}", e.getResourcePath());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 404);
        result.put("message", "资源不存在");
        return result;
    }

    @ExceptionHandler(UserException.class)
    public Map<String, Object> handleUserException(UserException e) {
        log.warn("业务异常：{}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", e.getCode());
        result.put("message", e.getMessage());
        return result;
    }

    /** @Validated 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Map<String, Object> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        log.warn("参数校验失败：{}", message);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", message);
        return result;
    }

    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleException(Exception e) {
        log.error("系统异常", e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务器内部错误");
        return result;
    }
}
