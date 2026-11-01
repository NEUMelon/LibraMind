package com.libramind.loan;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.PageResult;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.loan.LoanDtos.*;
import com.libramind.student.StudentController.Reason;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** 借书与还书接口，两个模块读取同一借阅事实，学生请求自动限制为本人。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "借还流通")
public class LoanController {

    private final LoanService loans;
    private final ActorService actors;

    public LoanController(LoanService loans, ActorService actors) {
        this.loans = loans;
        this.actors = actors;
    }

    @GetMapping({
        "/loans",
        "/returns",
        "/me/loans",
        "/me/loan-history",
        "/students/{studentId}/loans",
        "/students/{studentId}/loan-history"
    })
    @Operation(summary = "分页查询借还记录，学生仅能查看本人")
    public ApiResponse<PageResult<LoanView>> list(
            @PathVariable(required = false) Long studentId,
            @RequestParam(name = "studentId", required = false) Long queryStudentId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        boolean returns = request.getRequestURI().equals("/api/v1/returns");
        if (request.getRequestURI().endsWith("/me/loans")) {
            status = LoanStatus.ACTIVE;
        }
        if (status == null && request.getRequestURI().endsWith("/loan-history")) {
            status = LoanStatus.RETURNED;
        }
        return ApiResponse.ok(
                loans.list(
                        actors.current(),
                        studentId == null ? queryStudentId : studentId,
                        q,
                        status,
                        returns,
                        from,
                        to,
                        page,
                        size),
                RequestIdFilter.id(request));
    }

    @GetMapping({"/loans/{id}", "/returns/{id}"})
    @Operation(summary = "借阅详情（本人或管理员）")
    public ApiResponse<LoanView> detail(@PathVariable long id, HttpServletRequest request) {
        LoanView found = loans.find(actors.current(), id);
        if (request.getRequestURI().contains("/returns/")
                && found.status() != LoanStatus.RETURNED) {
            throw com.libramind.common.api.BusinessException.notFound();
        }
        return ApiResponse.ok(found, RequestIdFilter.id(request));
    }

    @PostMapping("/loans/preview")
    @Operation(summary = "批量借阅预检（不写库存，不保证提交时仍可借）")
    public ApiResponse<BorrowPreview> preview(@Valid @RequestBody BorrowInput input) {
        return ApiResponse.ok(loans.preview(actors.current(), input));
    }

    @PostMapping("/loans")
    @Operation(summary = "借书或批量借书，整批成功或回滚", description = "学生身份取自会话；支持 Idempotency-Key。")
    public ApiResponse<BorrowResult> borrow(
            @Valid @RequestBody BorrowInput input,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(
                loans.borrow(actors.current(), input, key), RequestIdFilter.id(request));
    }

    @PostMapping("/returns")
    @Operation(summary = "登记归还，可提供 loanId 或 accessionNo", description = "支持 Idempotency-Key。")
    public ApiResponse<LoanView> giveBack(
            @Valid @RequestBody ReturnInput input,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(
                loans.giveBack(actors.current(), input, key), RequestIdFilter.id(request));
    }

    @PostMapping("/loans/{id}/renew")
    @Operation(summary = "续借：仅ACTIVE、未逾期、未达到次数上限，从原应还日期延长30天")
    public ApiResponse<LoanView> renew(
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(loans.renew(actors.current(), id, key), RequestIdFilter.id(request));
    }

    @PatchMapping({"/loans/{id}", "/returns/{id}"})
    @Operation(summary = "更正日期或备注（管理员，原因必填）")
    public ApiResponse<LoanView> correct(
            @PathVariable long id,
            @Valid @RequestBody CorrectionInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                loans.correct(
                        actors.current(), id, input, request.getRequestURI().contains("/returns/")),
                RequestIdFilter.id(request));
    }

    @DeleteMapping("/loans/{id}")
    @Operation(summary = "作废误借阅（管理员，保留历史和审计）")
    public ApiResponse<LoanView> cancel(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        return ApiResponse.ok(
                loans.cancel(actors.current(), id, input.reason()), RequestIdFilter.id(request));
    }

    @DeleteMapping("/returns/{id}")
    @Operation(summary = "撤销误归还（管理员，不允许破坏后续流通）")
    public ApiResponse<LoanView> undo(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        return ApiResponse.ok(
                loans.undoReturn(actors.current(), id, input.reason()),
                RequestIdFilter.id(request));
    }

    @GetMapping("/me/borrowing-eligibility")
    @Operation(summary = "查询本人借阅资格，管理员代办可提供studentId")
    public ApiResponse<Eligibility> eligibility(
            @RequestParam(required = false) Long studentId, HttpServletRequest request) {
        return ApiResponse.ok(
                loans.eligibility(actors.current(), studentId), RequestIdFilter.id(request));
    }
}
