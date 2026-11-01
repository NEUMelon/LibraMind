package com.libramind.search;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.search.KnowledgeService.*;
import com.libramind.student.StudentController.Reason;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 管理可引用资料和查看索引健康状态，学生仅可读取允许访问的资料。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "知识与索引")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final VectorIndexService vectors;
    private final ActorService actors;

    public KnowledgeController(
            KnowledgeService knowledge, VectorIndexService vectors, ActorService actors) {
        this.knowledge = knowledge;
        this.vectors = vectors;
        this.actors = actors;
    }

    @GetMapping("/knowledge")
    @Operation(summary = "知识资料列表（管理员，最多100条）")
    public ApiResponse<List<DocumentView>> list(
            @RequestParam(required = false) Long editionId, HttpServletRequest request) {
        return ApiResponse.ok(
                knowledge.documents(actors.current(), editionId), RequestIdFilter.id(request));
    }

    @GetMapping("/knowledge/{id}")
    @Operation(summary = "查看有权访问的引用来源（需登录）")
    public ApiResponse<DocumentView> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(
                knowledge.document(actors.current(), id), RequestIdFilter.id(request));
    }

    @PostMapping("/knowledge")
    @Operation(summary = "添加简介、目录或本馆规则等资料（管理员）")
    public ApiResponse<DocumentView> create(
            @Valid @RequestBody KnowledgeInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                knowledge.create(actors.current(), input), RequestIdFilter.id(request));
    }

    @PatchMapping("/knowledge/{id}")
    @Operation(summary = "更新资料并使旧向量失效（管理员）")
    public ApiResponse<DocumentView> update(
            @PathVariable long id,
            @Valid @RequestBody KnowledgeInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                knowledge.update(actors.current(), id, input), RequestIdFilter.id(request));
    }

    @DeleteMapping("/knowledge/{id}")
    @Operation(summary = "禁用资料并登记索引删除任务（管理员）")
    public ApiResponse<Boolean> delete(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        knowledge.delete(actors.current(), id, input.reason());
        return ApiResponse.ok(true, RequestIdFilter.id(request));
    }

    @GetMapping("/admin/search/index-status")
    @Operation(summary = "索引健康与待处理数量（管理员）")
    public ApiResponse<VectorIndexService.IndexStatus> status(HttpServletRequest request) {
        actors.admin();
        return ApiResponse.ok(vectors.status(), RequestIdFilter.id(request));
    }
}
