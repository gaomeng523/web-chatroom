package com.example.chatroom.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    /**
     * 业务异常。
     * <p>
     * ⚠️ 必须把 code 落到<b>真正的 HTTP 状态码</b>上，不能只在 body 里带一个 code 字段。
     * 只放 body 的话浏览器收到的仍是 200，jQuery 会走 success 回调 ——
     * 前端的 error 分支（负责 alert 失败原因）永远不会执行，
     * 用户看到的就是"点了没反应"，而且 success 里还会拿到一个没有业务字段的错误体。
     * <p>
     * 顺带一提：拦截器返回 401 用的就是真 401，两边保持一致才不别扭。
     */
    @ExceptionHandler(UserException.class)
    public ResponseEntity<Map<String, Object>> handleUserException(UserException e) {
        log.warn("业务异常：{}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", e.getCode());
        result.put("message", e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getCode())).body(result);
    }

    /** code 按约定就是 HTTP 状态码（400 为主）；万一是非法值，兜底成 400 而不是抛异常 */
    private static HttpStatus httpStatusOf(Integer code) {
        HttpStatus status = (code == null) ? null : HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_REQUEST;
    }

    /**
     * Spring MVC 自己抛的「协议级」异常：HTTP 方法用错、请求体 JSON 格式不对、
     * 缺必填参数、参数类型不对、Content-Type 不支持……
     * <p>
     * ⚠️ 这些必须在下面那个 {@code Exception} 兜底 handler 之前被拦下。
     * 否则会被一起吞成 500「服务器内部错误」，前端拿到的提示完全牛头不对马嘴 ——
     * 比如把 POST 打到 GET 接口上，用户看到的是"服务器内部错误"，还以为是后端崩了。
     * <p>
     * Spring 挑 handler 时「子类优先、精确优先」，所以这里列出的类型不会走到兜底那一条。
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class
    })
    public ResponseEntity<Map<String, Object>> handleSpringMvcException(Exception e) {
        // 这些异常都实现了 ErrorResponse，自带正确的状态码（405 / 400 / 415 / 406），
        // 直接取出来用，不要自己猜。
        int status = (e instanceof ErrorResponse er)
                ? er.getStatusCode().value()
                : HttpStatus.BAD_REQUEST.value();
        log.warn("请求不合法：{} -> {}", e.getClass().getSimpleName(), e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", status);
        // 这些是协议错误，原文里没有敏感信息，直接透出比一句笼统的提示更有用
        result.put("message", e.getMessage());
        return ResponseEntity.status(status).body(result);
    }

    /** 上传文件超过 spring.servlet.multipart.max-request-size 时应该返回 413，而不是 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大：{}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", HttpStatus.PAYLOAD_TOO_LARGE.value());
        result.put("message", "文件太大了，请选择更小的文件");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(result);
    }

    /** @Validated 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
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
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        log.error("系统异常", e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务器内部错误");
        return result;
    }
}
