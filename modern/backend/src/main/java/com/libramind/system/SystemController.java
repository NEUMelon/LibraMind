package com.libramind.system;

import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供后端版本和文档入口，便于确认当前启动的是现代版基础工程。 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "系统", description = "版本与开发阶段信息")
public class SystemController {

    private final BuildProperties build;
    private final com.libramind.ai.AiGateway ai;

    /** 注入 Maven 生成的构建版本信息。 */
    public SystemController(BuildProperties build, com.libramind.ai.AiGateway ai) {
        this.build = build;
        this.ai = ai;
    }

    /** 返回工程版本、当前阶段和文档地址，不返回数据库凭据或模型密钥。 */
    @GetMapping("/info")
    @Operation(summary = "工程信息", description = "匿名可访问，不代表业务和 AI 模块已完成。")
    public ApiResponse<SystemInfo> info(HttpServletRequest request) {
        return ApiResponse.ok(
                new SystemInfo(
                        "LibraMind",
                        build.getVersion(),
                        "backend-first",
                        "/swagger-ui.html",
                        "/openapi.json",
                        ai.isLive()),
                RequestIdFilter.id(request));
    }

    /** 工程状态响应；aiEnabled=false 表示当前阶段没有启用模型调用。 */
    public record SystemInfo(
            String name,
            String version,
            String phase,
            String swaggerUrl,
            String openApiUrl,
            boolean aiEnabled) {}
}
