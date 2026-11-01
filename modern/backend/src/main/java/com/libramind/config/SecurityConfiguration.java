package com.libramind.config;

import com.libramind.auth.service.DatabaseUserDetailsService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/** 配置 Session 登录、CSRF 防护和接口权限，未登录请求返回 JSON 而不是登录页。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    /** 返回 BCrypt 编码器，数据库只保存密码哈希。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** 使用数据库账号和 BCrypt 进行用户名密码认证。 */
    @Bean
    AuthenticationManager authenticationManager(
            DatabaseUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** 将认证信息保存在服务端 Session，浏览器只持有 HttpOnly Cookie。 */
    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 将 CSRF Token 保存在 Session，写操作通过 X-CSRF-TOKEN 请求头提交。 */
    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /** 登录成功时更换 Session ID，并使登录前的 CSRF Token 失效。 */
    @Bean
    SessionAuthenticationStrategy loginSessionStrategy(HttpSessionCsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(csrf)));
    }

    /** 放行文档、健康检查和登录入口，其余接口要求登录，写操作保留 CSRF 检查。 */
    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value(
                            "${app.web.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                    String origins) {
        var configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOrigins(
                java.util.Arrays.stream(origins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Content-Type", "X-CSRF-TOKEN", "Idempotency-Key", "X-Request-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf,
            ObjectMapper json)
            throws Exception {
        return http.cors(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(
                        config ->
                                config.csrfTokenRepository(csrf)
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/books/**",
                                                "/api/v1/copies/**",
                                                "/api/v1/works/**",
                                                "/api/v1/covers/**",
                                                "/api/v1/search/books",
                                                "/assets/**",
                                                "/",
                                                "/index.html")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v1/auth/csrf",
                                                "/api/v1/auth/login",
                                                "/api/v1/system/info",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/openapi.json",
                                                "/openapi.json/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeError(
                                                                json,
                                                                request,
                                                                response,
                                                                401,
                                                                "AUTHENTICATION_REQUIRED",
                                                                "请先登录"))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeError(
                                                                json,
                                                                request,
                                                                response,
                                                                403,
                                                                exception instanceof CsrfException
                                                                        ? "CSRF_TOKEN_INVALID"
                                                                        : "ACCESS_DENIED",
                                                                exception instanceof CsrfException
                                                                        ? "请获取有效的 CSRF Token 后重试"
                                                                        : "没有执行此操作的权限")))
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    /** 把过滤器阶段的认证或权限异常写为与业务接口相同的 JSON 格式。 */
    private void writeError(
            ObjectMapper json,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        json.writeValue(
                response.getOutputStream(),
                ApiResponse.error(code, message, null, RequestIdFilter.id(request)));
    }
}
