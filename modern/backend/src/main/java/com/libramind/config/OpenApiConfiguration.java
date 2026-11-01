package com.libramind.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置接口文档标题和 Session、CSRF 的认证说明，只展示已经实现的接口。 */
@Configuration
public class OpenApiConfiguration {

    /** 为生成的每个操作补充真实Cookie/CSRF要求、通用错误和幂等约定。 */
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer contractDetails() {
        return api -> {
            var error =
                    new io.swagger.v3.oas.models.media.Schema<>()
                            .type("object")
                            .addProperty(
                                    "success",
                                    new io.swagger.v3.oas.models.media.BooleanSchema()
                                            ._default(false))
                            .addProperty("code", new io.swagger.v3.oas.models.media.StringSchema())
                            .addProperty(
                                    "message", new io.swagger.v3.oas.models.media.StringSchema())
                            .addProperty(
                                    "requestId", new io.swagger.v3.oas.models.media.StringSchema())
                            .addProperty(
                                    "details",
                                    new io.swagger.v3.oas.models.media.MapSchema()
                                            .additionalProperties(
                                                    new io.swagger.v3.oas.models.media
                                                            .StringSchema()));
            api.getComponents().addSchemas("ErrorResponse", error);
            api.getPaths()
                    .forEach(
                            (path, item) ->
                                    item.readOperationsMap()
                                            .forEach(
                                                    (method, operation) -> {
                                                        boolean read =
                                                                method
                                                                        == io.swagger.v3.oas.models
                                                                                .PathItem.HttpMethod
                                                                                .GET;
                                                        boolean publicRead =
                                                                read
                                                                        && (path.startsWith(
                                                                                        "/api/v1/books")
                                                                                || path.startsWith(
                                                                                        "/api/v1/copies")
                                                                                || path.startsWith(
                                                                                        "/api/v1/works")
                                                                                || path.startsWith(
                                                                                        "/api/v1/covers")
                                                                                || path.equals(
                                                                                        "/api/v1/search/books")
                                                                                || path.equals(
                                                                                        "/api/v1/system/info")
                                                                                || path.equals(
                                                                                        "/api/v1/auth/csrf"));
                                                        boolean login =
                                                                path.equals("/api/v1/auth/login");
                                                        var requirement =
                                                                new io.swagger.v3.oas.models
                                                                        .security
                                                                        .SecurityRequirement();
                                                        if (!publicRead && !login) {
                                                            requirement.addList("sessionCookie");
                                                        }
                                                        if (!read) {
                                                            requirement.addList("csrfHeader");
                                                        }
                                                        operation.setSecurity(
                                                                requirement.isEmpty()
                                                                        ? java.util.List.of()
                                                                        : java.util.List.of(
                                                                                requirement));
                                                        boolean idempotent =
                                                                !read
                                                                        && (path.equals(
                                                                                        "/api/v1/loans")
                                                                                || path.equals(
                                                                                        "/api/v1/returns")
                                                                                || path.endsWith(
                                                                                        "/renew")
                                                                                || path.equals(
                                                                                        "/api/v1/import/books/execute"));
                                                        operation.addExtension(
                                                                "x-idempotency-key-supported",
                                                                idempotent);
                                                        if (path.endsWith("/stream")) {
                                                            operation.addExtension(
                                                                    "x-sse-events",
                                                                    java.util.List.of(
                                                                            "meta", "tool", "delta",
                                                                            "result", "done",
                                                                            "error"));
                                                        }
                                                        for (String code :
                                                                java.util.List.of(
                                                                        "400", "401", "403", "404",
                                                                        "409", "500")) {
                                                            operation
                                                                    .getResponses()
                                                                    .addApiResponse(
                                                                            code,
                                                                            new io.swagger.v3.oas
                                                                                            .models
                                                                                            .responses
                                                                                            .ApiResponse()
                                                                                    .description(
                                                                                            "统一错误响应，错误码与业务规则见API_CONTRACT.md")
                                                                                    .content(
                                                                                            new io
                                                                                                            .swagger
                                                                                                            .v3
                                                                                                            .oas
                                                                                                            .models
                                                                                                            .media
                                                                                                            .Content()
                                                                                                    .addMediaType(
                                                                                                            "application/json",
                                                                                                            new io
                                                                                                                            .swagger
                                                                                                                            .v3
                                                                                                                            .oas
                                                                                                                            .models
                                                                                                                            .media
                                                                                                                            .MediaType()
                                                                                                                    .schema(
                                                                                                                            new io
                                                                                                                                            .swagger
                                                                                                                                            .v3
                                                                                                                                            .oas
                                                                                                                                            .models
                                                                                                                                            .media
                                                                                                                                            .Schema<>()
                                                                                                                                    .$ref(
                                                                                                                                            "#/components/schemas/ErrorResponse")))));
                                                        }
                                                    }));
        };
    }

    /** 返回 OpenAPI 元数据；浏览器会自动携带登录 Cookie，写操作另需 CSRF 请求头。 */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("LibraMind Backend API")
                                .version("0.1.0")
                                .description(
                                        "现代版图书馆后端。先 GET /api/v1/auth/csrf，保留 Session Cookie，"
                                                + "再用 X-CSRF-TOKEN 头登录。登录后重新获取 Token。"
                                                + "包含馆藏、学生、流通、识图、检索、知识与受控AI工具。时间统一使用ISO-8601 UTC。"
                                                + "AI确认必须走actions/{id}/confirm，prepare不写借还业务。"
                                                + "SSE事件和前端接入说明见docs/FRONTEND_HANDOFF.md。"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "sessionCookie",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("JSESSIONID"))
                                .addSecuritySchemes(
                                        "csrfHeader",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-CSRF-TOKEN")));
    }
}
