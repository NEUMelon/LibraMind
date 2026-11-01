package com.libramind.common.api;

import org.springframework.http.HttpStatus;

/** 可向用户说明的业务失败，携带稳定错误码与 HTTP 状态。 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    /** 根据状态、业务错误码和用户提示创建异常。 */
    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    /** 资源不存在或当前用户不可见时返回同一种错误。 */
    public static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在");
    }

    /** 业务状态不允许本次操作时返回冲突。 */
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    /** 输入的业务参数不合法时返回 400。 */
    public static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
