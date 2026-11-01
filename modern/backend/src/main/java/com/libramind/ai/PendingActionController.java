package com.libramind.ai;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

/** 普通 REST 确认入口，AI 工具不能调用 confirm 或 cancel。 */
@RestController
@RequestMapping("/api/v1/ai/actions")
public class PendingActionController {
    public record BorrowPreparation(
            Long conversationId, Long studentId, @NotBlank String accessionNo) {}

    public record LoanPreparation(Long conversationId, @Positive long loanId) {}

    private final ActorService actors;
    private final PendingActionService actions;

    public PendingActionController(ActorService actors, PendingActionService actions) {
        this.actors = actors;
        this.actions = actions;
    }

    @PostMapping("/prepare-borrow")
    public ApiResponse<PendingActionService.ActionView> borrow(
            @Valid @RequestBody BorrowPreparation input) {
        return ApiResponse.ok(
                actions.prepareBorrow(
                        actors.current(),
                        input.conversationId(),
                        input.studentId(),
                        input.accessionNo()));
    }

    @PostMapping("/prepare-renew")
    public ApiResponse<PendingActionService.ActionView> renew(
            @Valid @RequestBody LoanPreparation input) {
        return ApiResponse.ok(
                actions.prepareLoan(
                        actors.current(), input.conversationId(), input.loanId(), "RENEW"));
    }

    @PostMapping("/prepare-return")
    public ApiResponse<PendingActionService.ActionView> giveBack(
            @Valid @RequestBody LoanPreparation input) {
        return ApiResponse.ok(
                actions.prepareLoan(
                        actors.current(), input.conversationId(), input.loanId(), "RETURN"));
    }

    @GetMapping("/{id}")
    public ApiResponse<PendingActionService.ActionView> get(@PathVariable String id) {
        return ApiResponse.ok(actions.get(actors.current(), id));
    }

    @GetMapping
    public ApiResponse<java.util.List<PendingActionService.ActionView>> list(
            @RequestParam long conversationId) {
        return ApiResponse.ok(actions.list(actors.current(), conversationId));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<PendingActionService.ActionView> confirm(@PathVariable String id) {
        return ApiResponse.ok(actions.confirm(actors.current(), id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PendingActionService.ActionView> cancel(@PathVariable String id) {
        return ApiResponse.ok(actions.cancel(actors.current(), id));
    }
}
