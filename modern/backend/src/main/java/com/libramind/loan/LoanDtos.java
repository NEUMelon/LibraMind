package com.libramind.loan;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/** 借阅登记、历史维护与查询 DTO，所有时间使用带时区的 ISO-8601。 */
public final class LoanDtos {
    private LoanDtos() {}

    public enum LoanStatus {
        ACTIVE,
        RETURNED,
        CANCELLED
    }

    public record BorrowInput(
            Long studentId,
            @NotEmpty @Size(max = 10) List<@NotBlank String> accessionNos,
            @Size(max = 1000) String note) {}

    public record ReturnInput(Long loanId, String accessionNo, @Size(max = 1000) String note) {}

    public record CorrectionInput(
            Instant borrowedAt,
            Instant dueAt,
            Instant returnedAt,
            @Size(max = 1000) String note,
            @NotBlank @Size(max = 500) String reason) {}

    public record LoanView(
            long id,
            long studentId,
            String studentNo,
            String studentName,
            long copyId,
            String accessionNo,
            long editionId,
            String title,
            Instant borrowedAt,
            Instant dueAt,
            Instant returnedAt,
            LoanStatus status,
            int renewCount,
            boolean overdue,
            String note,
            String cancellationReason) {}

    public record BorrowResult(List<LoanView> loans) {}

    public record CopyCheck(
            String accessionNo,
            com.libramind.catalog.CatalogDtos.CopyView copy,
            boolean available,
            String reason) {}

    public record BorrowPreview(Eligibility eligibility, List<CopyCheck> copies, boolean valid) {}

    public record Eligibility(
            long studentId,
            boolean enabled,
            int currentBorrowedCount,
            int limit,
            int remaining,
            long overdueCount,
            boolean canBorrow) {}
}
