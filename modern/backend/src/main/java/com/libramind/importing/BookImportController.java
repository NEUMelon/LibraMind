package com.libramind.importing;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.importing.BookImportService.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

/** 管理员书目导入入口，支持预览、完整校验与事务执行。 */
@RestController
@RequestMapping("/api/v1/import/books")
@Tag(name = "数据导入")
public class BookImportController {

    private final BookImportService importing;
    private final ActorService actors;

    public BookImportController(BookImportService importing, ActorService actors) {
        this.importing = importing;
        this.actors = actors;
    }

    @PostMapping("/preview")
    @Operation(summary = "导入预览（不写库，管理员）")
    public ApiResponse<ImportPreview> preview(
            @RequestBody ImportInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                importing.preview(actors.current(), input), RequestIdFilter.id(request));
    }

    @PostMapping("/execute")
    @Operation(summary = "整批导入书目（管理员，支持Idempotency-Key，任一失败整批回滚）")
    public ApiResponse<ImportResult> execute(
            @RequestBody ImportInput input,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(
                importing.execute(actors.current(), input, key), RequestIdFilter.id(request));
    }
}
