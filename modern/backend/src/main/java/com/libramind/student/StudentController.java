package com.libramind.student;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.PageResult;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.student.StudentDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 学生管理接口，管理员维护资料，学生只能读取自己的资料。 */
@RestController
@RequestMapping("/api/v1/students")
@Tag(name = "学生管理")
public class StudentController {

    private final StudentService students;
    private final ActorService actors;

    public StudentController(StudentService students, ActorService actors) {
        this.students = students;
        this.actors = actors;
    }

    @GetMapping
    @Operation(summary = "学生分页列表（管理员）")
    public ApiResponse<PageResult<StudentView>> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(
                students.list(actors.current(), q, state, page, size), RequestIdFilter.id(request));
    }

    @GetMapping("/by-number/{number}")
    @Operation(summary = "管理员按完整学号精确查询")
    public ApiResponse<StudentView> number(@PathVariable String number) {
        return ApiResponse.ok(students.findByNumber(actors.admin(), number));
    }

    @GetMapping("/{id}")
    @Operation(summary = "学生详情（本人或管理员）")
    public ApiResponse<StudentView> find(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(students.find(actors.current(), id), RequestIdFilter.id(request));
    }

    @PostMapping
    @Operation(summary = "新增学生（管理员）")
    public ApiResponse<StudentView> create(
            @Valid @RequestBody StudentInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                students.create(actors.current(), input), RequestIdFilter.id(request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "更新学生资料（管理员，提交全部可编辑字段）")
    public ApiResponse<StudentView> update(
            @PathVariable long id,
            @Valid @RequestBody StudentInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                students.update(actors.current(), id, input), RequestIdFilter.id(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除无关联学生或停用历史学生（管理员）")
    public ApiResponse<String> delete(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        return ApiResponse.ok(
                students.delete(actors.current(), id, input.reason()), RequestIdFilter.id(request));
    }

    @PostMapping("/{id}/account")
    @Operation(summary = "为学生开通账号（管理员）")
    public ApiResponse<AccountCreated> account(
            @PathVariable long id,
            @Valid @RequestBody AccountInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                students.createAccount(actors.current(), id, input), RequestIdFilter.id(request));
    }

    public record Reason(String reason) {}
}
