package com.libramind.vision;

import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDtos.BookInput;
import com.libramind.catalog.CatalogDtos.BookView;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.vision.VisionService.VisionResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 多模态识书入口，模型提取与用户确认分成独立步骤。 */
@RestController
@RequestMapping("/api/v1/vision")
@Tag(name = "多模态识书")
public class VisionController {

    public record ChoiceInput(Long editionId) {}

    private final VisionService vision;
    private final ActorService actors;

    public VisionController(VisionService vision, ActorService actors) {
        this.vision = vision;
        this.actors = actors;
    }

    @PostMapping(
            value = {"/book-cover", "/copyright-page"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "拍封面找书或版权页预填，后者仅管理员可用", description = "固定低价qwen3.5-flash；用户图片不默认落盘。")
    public ApiResponse<VisionResult> recognize(
            @RequestPart MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(
                vision.recognize(
                        actors.current(),
                        file,
                        request.getRequestURI().endsWith("/copyright-page")),
                RequestIdFilter.id(request));
    }

    @PostMapping("/recognitions/{id}/choice")
    @Operation(summary = "确认或否定识书候选（本人操作，editionId为空表示否定）")
    public ApiResponse<Boolean> choice(
            @PathVariable long id, @RequestBody ChoiceInput input, HttpServletRequest request) {
        return ApiResponse.ok(
                vision.choose(actors.current(), id, input.editionId()),
                RequestIdFilter.id(request));
    }

    @PostMapping("/copyright-page/{id}/confirm")
    @Operation(summary = "人工核对完整表单后确认编目（管理员）")
    public ApiResponse<BookView> confirm(
            @PathVariable long id,
            @Valid @RequestBody BookInput input,
            HttpServletRequest request) {
        return ApiResponse.ok(
                vision.confirmCopyright(actors.current(), id, input), RequestIdFilter.id(request));
    }
}
