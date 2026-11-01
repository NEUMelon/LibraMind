package com.libramind.ai;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.PageResult;
import com.libramind.common.db.SqlStore;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;

/** 管理员检查模型费用与调用状态，只返回元数据，不返回请求正文或密钥。 */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AiOperationsController {
    public record Usage(
            long id,
            String type,
            String model,
            String status,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedUsd,
            long latencyMs,
            String errorCode,
            Instant createdAt) {}

    private final SqlStore sql;
    private final ActorService actors;

    public AiOperationsController(SqlStore sql, ActorService actors) {
        this.sql = sql;
        this.actors = actors;
    }

    @GetMapping("/requests")
    public ApiResponse<PageResult<Usage>> requests(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        actors.admin();
        PageResult.validate(page, size);
        var rows =
                sql
                        .list(
                                "SELECT * FROM ai_request ORDER BY id DESC LIMIT ? OFFSET ?",
                                size,
                                (page - 1L) * size)
                        .stream()
                        .map(
                                r ->
                                        new Usage(
                                                number(r, "id"),
                                                text(r, "request_type"),
                                                text(r, "model"),
                                                text(r, "status"),
                                                number(r, "input_tokens"),
                                                number(r, "output_tokens"),
                                                (BigDecimal) r.get("estimated_usd"),
                                                number(r, "latency_ms"),
                                                text(r, "error_code"),
                                                instant(r, "created_at")))
                        .toList();
        return ApiResponse.ok(
                PageResult.of(rows, page, size, sql.count("SELECT COUNT(*) FROM ai_request")));
    }
}
