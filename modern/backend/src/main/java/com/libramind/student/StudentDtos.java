package com.libramind.student;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 学生管理的输入与返回对象，已借数量来自有效在借记录。 */
public final class StudentDtos {
    private StudentDtos() {}

    public record StudentInput(
            @NotBlank @Pattern(regexp = "[0-9]{4,32}", message = "学号应为4到32位数字") String studentNo,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 120) String college,
            @NotBlank @Size(max = 120) String major,
            boolean enabled) {}

    public record StudentView(
            long id,
            String studentNo,
            String name,
            String college,
            String major,
            boolean enabled,
            long currentBorrowedCount) {}

    public record AccountInput(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 12, max = 64) String password) {}

    public record AccountCreated(long id, String username, String role, long studentId) {}
}
