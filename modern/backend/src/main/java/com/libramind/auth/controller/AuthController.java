package com.libramind.auth.controller;

import com.libramind.auth.dto.AuthDtos.CsrfResponse;
import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.dto.AuthDtos.LoginRequest;
import com.libramind.auth.service.AuthService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录接口入口，提供 CSRF Token、登录、当前账号查询与退出。 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "Session Cookie 登录；所有写请求都需 CSRF Token")
public class AuthController {

    private final AuthService auth;

    /** 注入登录服务。 */
    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** 获取当前 Session 的 CSRF Token，调用方需要保留响应中的 Cookie。 */
    @GetMapping("/csrf")
    @Operation(summary = "获取 CSRF Token", description = "匿名可访问。登录后应重新获取一次。")
    public ApiResponse<CsrfResponse> csrf(CsrfToken token, HttpServletRequest request) {
        return ApiResponse.ok(
                new CsrfResponse(token.getHeaderName(), token.getToken()),
                RequestIdFilter.id(request));
    }

    /** 校验用户名密码并建立会话，返回账号 ID、用户名和角色。 */
    @PostMapping("/login")
    @Operation(summary = "登录", security = @SecurityRequirement(name = "csrfHeader"))
    public ApiResponse<CurrentUser> login(
            @Valid @RequestBody LoginRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ApiResponse.ok(auth.login(input, request, response), RequestIdFilter.id(request));
    }

    /** 查询当前登录账号，未登录时返回 401。 */
    @GetMapping("/me")
    @Operation(summary = "当前账号", security = @SecurityRequirement(name = "sessionCookie"))
    public ApiResponse<CurrentUser> me(Authentication authentication, HttpServletRequest request) {
        return ApiResponse.ok(auth.currentUser(authentication), RequestIdFilter.id(request));
    }

    /** 退出当前会话，成功时 data 为 true。 */
    @PostMapping("/logout")
    @Operation(
            summary = "退出登录",
            security = {
                @SecurityRequirement(name = "sessionCookie"),
                @SecurityRequirement(name = "csrfHeader")
            })
    public ApiResponse<Boolean> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        auth.logout(request, response, authentication);
        return ApiResponse.ok(true, RequestIdFilter.id(request));
    }
}
