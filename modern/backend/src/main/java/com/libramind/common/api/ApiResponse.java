package com.libramind.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** 业务接口的统一返回格式，包含业务数据或错误信息，以及用于排查请求的 requestId。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String code,
        String message,
        Map<String, String> details,
        String requestId) {

    /** 将 data 包装为成功响应，requestId 用于关联服务端日志。 */
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, null, null, requestId);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, org.slf4j.MDC.get("requestId"));
    }

    /** 根据错误码、说明和字段错误生成失败响应，不包含内部异常信息。 */
    public static ApiResponse<Void> error(
            String code, String message, Map<String, String> details, String requestId) {
        return new ApiResponse<>(false, null, code, message, details, requestId);
    }
}
