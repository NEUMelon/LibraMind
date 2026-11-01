package com.libramind.catalog;

import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;
import com.libramind.common.api.PageResult;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.student.StudentController.Reason;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 馆藏接口，公开查询书目与位置，管理员维护作品、版本及实体副本。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "图书管理")
public class CatalogController {

    private final CatalogDAO catalog;
    private final CatalogService service;
    private final ActorService actors;

    public CatalogController(CatalogDAO catalog, CatalogService service, ActorService actors) {
        this.catalog = catalog;
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/books")
    @Operation(summary = "版本分页查询（公开）")
    public ApiResponse<PageResult<BookView>> books(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "false") boolean available,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(
                catalog.books(q, available, page, size, sort), RequestIdFilter.id(request));
    }

    @GetMapping("/books/{id}")
    @Operation(summary = "版本详情（公开）")
    public ApiResponse<BookView> book(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.book(id), RequestIdFilter.id(request));
    }

    @PostMapping("/books")
    @Operation(summary = "新增出版版本（管理员）")
    public ApiResponse<BookView> createBook(
            @Valid @RequestBody BookInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                service.createBook(actors.current(), input), RequestIdFilter.id(request));
    }

    @PatchMapping("/books/{id}")
    @Operation(summary = "更新版本元数据（管理员，提交全部可编辑字段）")
    public ApiResponse<BookView> updateBook(
            @PathVariable long id,
            @Valid @RequestBody BookInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.updateBook(actors.current(), id, input), RequestIdFilter.id(request));
    }

    @DeleteMapping("/books/{id}")
    @Operation(summary = "归档无副本版本（管理员）")
    public ApiResponse<String> deleteBook(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        return ApiResponse.ok(
                service.deleteBook(actors.current(), id, input.reason()),
                RequestIdFilter.id(request));
    }

    @GetMapping("/works/{id}")
    @Operation(summary = "作品及其版本、馆藏汇总（公开）")
    public ApiResponse<WorkView> work(@PathVariable long id, HttpServletRequest request) {
        WorkView work = catalog.findWork(id);
        if (work == null) {
            throw BusinessException.notFound();
        }
        return ApiResponse.ok(work, RequestIdFilter.id(request));
    }

    @PatchMapping("/editions/{id}/work")
    @Operation(summary = "人工指定作品分组，workId为空表示移到新分组（管理员）")
    public ApiResponse<WorkView> assign(
            @PathVariable long id,
            @Valid @RequestBody WorkAssignment input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.assignWork(actors.current(), id, input), RequestIdFilter.id(request));
    }

    @GetMapping({"/copies", "/books/{editionId}/copies"})
    @Operation(summary = "馆藏副本分页，联合展示教师要求的五项信息（公开）")
    public ApiResponse<PageResult<CopyView>> copies(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) CopyStatus status,
            @PathVariable(required = false) Long editionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(
                catalog.copies(q, status, editionId, page, size), RequestIdFilter.id(request));
    }

    @GetMapping("/copies/{id}")
    @Operation(summary = "馆藏副本详情（公开）")
    public ApiResponse<CopyView> copy(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.copy(id), RequestIdFilter.id(request));
    }

    @GetMapping("/copies/by-accession/{accessionNo}")
    @Operation(summary = "按馆藏登录号精确查询（公开）")
    public ApiResponse<CopyView> accession(
            @PathVariable String accessionNo, HttpServletRequest request) {
        CopyView copy = catalog.findAccession(accessionNo);
        if (copy == null) {
            throw BusinessException.notFound();
        }
        return ApiResponse.ok(copy, RequestIdFilter.id(request));
    }

    @PostMapping("/copies")
    @Operation(summary = "新增实体馆藏（管理员）")
    public ApiResponse<CopyView> createCopy(
            @Valid @RequestBody CopyInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                service.createCopy(actors.current(), input), RequestIdFilter.id(request));
    }

    @PatchMapping("/copies/{id}")
    @Operation(summary = "维护馆藏位置与非流通状态（管理员）")
    public ApiResponse<CopyView> updateCopy(
            @PathVariable long id,
            @Valid @RequestBody CopyInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                service.updateCopy(actors.current(), id, input), RequestIdFilter.id(request));
    }

    @DeleteMapping("/copies/{id}")
    @Operation(summary = "删除无历史副本或下架历史副本（管理员）")
    public ApiResponse<String> deleteCopy(
            @PathVariable long id, @RequestBody Reason input, HttpServletRequest request) {
        return ApiResponse.ok(
                service.deleteCopy(actors.current(), id, input.reason()),
                RequestIdFilter.id(request));
    }
}
