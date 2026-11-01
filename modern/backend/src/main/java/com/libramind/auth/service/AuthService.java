package com.libramind.auth.service;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.dto.AuthDtos.LoginRequest;
import com.libramind.auth.mapper.AccountMapper;
import com.libramind.auth.model.Account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

/** 处理账号登录、Session 保存、当前账号查询和退出，不向接口暴露认证内部对象。 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final AccountMapper accounts;

    /** 注入认证器、Session 存储、登录后会话策略与账号查询接口。 */
    public AuthService(
            AuthenticationManager authenticationManager,
            HttpSessionSecurityContextRepository contexts,
            SessionAuthenticationStrategy sessionStrategy,
            AccountMapper accounts) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.sessionStrategy = sessionStrategy;
        this.accounts = accounts;
    }

    /** 校验输入的账号密码，轮换会话并保存认证信息，返回当前账号的公开信息。 */
    public CurrentUser login(
            LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        Authentication authenticated =
                authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(
                                input.username(), input.password()));
        sessionStrategy.onAuthentication(authenticated, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return currentUser(authenticated);
    }

    /** 按已认证的用户名查询当前账号；账号不存在或停用时不返回个人数据。 */
    public CurrentUser currentUser(Authentication authentication) {
        Account account =
                authentication == null ? null : accounts.findByUsername(authentication.getName());
        if (account == null || !account.enabled()) {
            throw new AuthenticationCredentialsNotFoundException("Account is not available");
        }
        return new CurrentUser(
                account.id(), account.username(), account.role(), account.studentId());
    }

    /** 清除认证上下文、使 Session 失效并删除浏览器会话 Cookie。 */
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
    }
}
