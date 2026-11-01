package com.libramind.common.web;

import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把参数、认证和系统异常转换为统一错误响应，内部异常只写入服务端日志。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidParameter(HttpServletRequest request) {
        return error("INVALID_ARGUMENT", "请求参数缺失或格式不正确", request);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> oversized(HttpServletRequest request) {
        return error("IMAGE_TOO_LARGE", "上传文件不能超过10MB", request);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> method(HttpServletRequest request) {
        return error("METHOD_NOT_ALLOWED", "该资源不支持此请求方法", request);
    }

    /** 返回可以向用户说明的业务错误。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(
            BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(error(exception.code(), exception.getMessage(), request));
    }

    /** 数据库唯一约束或关联约束失败时返回冲突，不暴露 SQL。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> constraint(HttpServletRequest request) {
        return error("DATA_CONFLICT", "记录重复、仍有关联数据或状态不符合约束", request);
    }

    /** 返回字段校验错误，details 的键是字段名，值是校验失败的原因。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(
                        ApiResponse.error(
                                "VALIDATION_FAILED",
                                "请检查输入字段",
                                details,
                                RequestIdFilter.id(request)));
    }

    /** 请求体无法解析时返回 400，不回显包含敏感内容的原始请求。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> invalidJson(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(error("INVALID_REQUEST_BODY", "请求体格式不正确", request));
    }

    /** 登录失败统一返回相同提示，避免泄露账号是否存在。 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> authentication(HttpServletRequest request) {
        return error("AUTHENTICATION_FAILED", "用户名或密码不正确", request);
    }

    /** 已登录但没有操作权限时返回 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> forbidden(HttpServletRequest request) {
        return error("ACCESS_DENIED", "没有执行此操作的权限", request);
    }

    /** 请求不存在的资源时返回 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> notFound(HttpServletRequest request) {
        return error("NOT_FOUND", "资源不存在", request);
    }

    /** 未预期的异常返回通用说明，通过 requestId 在服务端排查。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Request {} failed", RequestIdFilter.id(request), exception);
        return error("INTERNAL_ERROR", "服务暂时无法处理请求", request);
    }

    /** 生成没有字段错误的统一失败响应。 */
    private ApiResponse<Void> error(String code, String message, HttpServletRequest request) {
        return ApiResponse.error(code, message, null, RequestIdFilter.id(request));
    }
}
