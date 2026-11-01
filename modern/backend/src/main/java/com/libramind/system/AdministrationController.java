package com.libramind.system;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;
import com.libramind.common.api.PageResult;
import com.libramind.common.db.SqlStore;
import com.libramind.common.web.RequestIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 管理员统计和审计查询，所有入口都先验证管理员身份。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "统计与审计")
public class AdministrationController {

    public record Overview(
            long students,
            long editions,
            long copies,
            long available,
            long activeLoans,
            long overdueLoans) {}

    public record DayCount(String date, long count) {}

    public record AuditView(
            long id,
            long operatorId,
            String operationType,
            String targetType,
            long targetId,
            String beforeSnapshot,
            String afterSnapshot,
            String reason,
            String requestId,
            Instant createdAt) {}

    private final ActorService actors;
    private final SqlStore sql;

    public AdministrationController(ActorService actors, SqlStore sql) {
        this.actors = actors;
        this.sql = sql;
    }

    @GetMapping("/dashboard/overview")
    @Operation(summary = "管理概况（管理员）")
    public ApiResponse<Overview> overview(HttpServletRequest request) {
        actors.admin();
        return ApiResponse.ok(
                new Overview(
                        sql.count("SELECT COUNT(*) FROM student"),
                        sql.count("SELECT COUNT(*) FROM book_edition WHERE archived=FALSE"),
                        sql.count("SELECT COUNT(*) FROM book_copy WHERE archived=FALSE"),
                        sql.count(
                                "SELECT COUNT(*) FROM book_copy WHERE archived=FALSE AND"
                                        + " circulation_status='AVAILABLE'"),
                        sql.count("SELECT COUNT(*) FROM loan WHERE status='ACTIVE'"),
                        sql.count(
                                "SELECT COUNT(*) FROM loan WHERE status='ACTIVE' AND"
                                        + " due_at<UTC_TIMESTAMP(6)")),
                RequestIdFilter.id(request));
    }

    @GetMapping("/dashboard/loan-trend")
    @Operation(summary = "近30天每日借阅次数（管理员，按UTC日统计）")
    public ApiResponse<List<DayCount>> trend(HttpServletRequest request) {
        actors.admin();
        var rows =
                sql.list(
                        "SELECT DATE(borrowed_at) AS day,COUNT(*) AS total FROM loan WHERE"
                            + " borrowed_at>=? AND status<>'CANCELLED' GROUP BY DATE(borrowed_at)"
                            + " ORDER BY day",
                        Instant.now().minus(30, ChronoUnit.DAYS));
        return ApiResponse.ok(
                rows.stream()
                        .map(row -> new DayCount(text(row, "day"), number(row, "total")))
                        .toList(),
                RequestIdFilter.id(request));
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "审计日志分页（管理员）")
    public ApiResponse<PageResult<AuditView>> audits(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        actors.admin();
        PageResult.validate(page, size);
        long count = sql.count("SELECT COUNT(*) FROM audit_log");
        var rows =
                sql.list(
                        "SELECT * FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?",
                        size,
                        (page - 1L) * size);
        return ApiResponse.ok(
                PageResult.of(rows.stream().map(this::audit).toList(), page, size, count),
                RequestIdFilter.id(request));
    }

    @GetMapping("/audit-logs/{id}")
    @Operation(summary = "审计日志详情（管理员）")
    public ApiResponse<AuditView> auditDetail(@PathVariable long id, HttpServletRequest request) {
        actors.admin();
        var row = sql.one("SELECT * FROM audit_log WHERE id=?", id);
        if (row == null) {
            throw BusinessException.notFound();
        }
        return ApiResponse.ok(audit(row), RequestIdFilter.id(request));
    }

    private AuditView audit(java.util.Map<String, Object> row) {
        return new AuditView(
                number(row, "id"),
                number(row, "operator_id"),
                text(row, "operation_type"),
                text(row, "target_type"),
                number(row, "target_id"),
                text(row, "before_snapshot"),
                text(row, "after_snapshot"),
                text(row, "reason"),
                text(row, "request_id"),
                instant(row, "created_at"));
    }
}
