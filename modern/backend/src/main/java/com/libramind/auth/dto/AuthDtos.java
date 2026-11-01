package com.libramind.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录接口使用的数据对象，分别表示登录输入、当前账号和 CSRF Token。 */
public final class AuthDtos {

    private AuthDtos() {}

    /** 用户名和密码输入；密码只用于本次认证，不作为响应返回。 */
    public record LoginRequest(
            @NotBlank(message = "请输入用户名") @Size(max = 64, message = "用户名不能超过64个字符") String username,
            @NotBlank(message = "请输入密码")
                    @Size(max = 64, message = "密码不能超过64个字符")
                    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password")
                    String password) {}

    /** 当前账号的公开信息，不包含密码或密码哈希。 */
    public record CurrentUser(long id, String username, String role, Long studentId) {}

    /** 写请求需要提交的请求头名称与 Token 值。 */
    public record CsrfResponse(String headerName, String token) {}
}
