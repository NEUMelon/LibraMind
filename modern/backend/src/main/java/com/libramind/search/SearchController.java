package com.libramind.search;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.search.SearchService.SearchResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

/** 检索接口，公开关键词查询不产生模型费用，语义与混合查询要求登录。 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "馆藏检索")
public class SearchController {

    public record SearchInput(
            @NotBlank @Size(max = 500) String query,
            @Min(1) @Max(20) int limit,
            boolean availableOnly) {}

    private final SearchService search;
    private final ActorService actors;

    public SearchController(SearchService search, ActorService actors) {
        this.search = search;
        this.actors = actors;
    }

    @GetMapping("/books")
    @Operation(summary = "关键词或ISBN检索（公开，不调用模型）")
    public ApiResponse<SearchResult> keyword(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            HttpServletRequest request) {
        return ApiResponse.ok(
                search.search(null, q, "keyword", limit, availableOnly),
                RequestIdFilter.id(request));
    }

    @PostMapping({"/semantic", "/hybrid"})
    @Operation(summary = "语义或混合检索（需登录，向量不可用时标记降级）")
    public ApiResponse<SearchResult> semantic(
            @Valid @RequestBody SearchInput input, HttpServletRequest request) {
        String mode = request.getRequestURI().endsWith("/semantic") ? "vector" : "hybrid";
        return ApiResponse.ok(
                search.search(
                        actors.current(),
                        input.query(),
                        mode,
                        input.limit(),
                        input.availableOnly()),
                RequestIdFilter.id(request));
    }
}
