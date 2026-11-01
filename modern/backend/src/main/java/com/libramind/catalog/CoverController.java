package com.libramind.catalog;

import com.libramind.auth.service.ActorService;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.AuditService;
import com.libramind.common.db.SqlStore;
import com.libramind.common.web.RequestIdFilter;
import com.libramind.vision.ImageService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 本地封面存储接口，数据库保存相对文件名，文件使用服务端生成名称。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "图书封面")
public class CoverController {

    public record CoverResult(String coverUrl) {}

    private final Path root;
    private final ImageService images;
    private final CatalogService catalog;
    private final ActorService actors;
    private final SqlStore sql;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public CoverController(
            @Value("${app.storage.cover-directory:./data/covers}") String directory,
            ImageService images,
            CatalogService catalog,
            ActorService actors,
            SqlStore sql,
            AuditService audit,
            PlatformTransactionManager manager)
            throws IOException {
        Files.createDirectories(Path.of(directory));
        this.root = Path.of(directory).toRealPath();
        this.images = images;
        this.catalog = catalog;
        this.actors = actors;
        this.sql = sql;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
    }

    @PostMapping(value = "/books/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传图书封面（管理员，真实图片，不超过10MB）")
    public ApiResponse<CoverResult> upload(
            @PathVariable long id, @RequestPart MultipartFile file, HttpServletRequest request)
            throws IOException {
        var actor = actors.admin();
        var image = images.read(file);
        String filename = UUID.randomUUID() + "." + image.extension();
        Path target = root.resolve(filename);
        Files.write(target, image.bytes(), StandardOpenOption.CREATE_NEW);
        try {
            transaction.executeWithoutResult(
                    status -> {
                        if (sql.one(
                                        "SELECT id FROM book_edition WHERE id=? AND archived=FALSE"
                                                + " FOR UPDATE",
                                        id)
                                == null) {
                            throw BusinessException.notFound();
                        }
                        var before = catalog.book(id);
                        sql.update("UPDATE book_edition SET cover_path=? WHERE id=?", filename, id);
                        audit.record(
                                actor,
                                "COVER_UPLOAD",
                                "EDITION",
                                id,
                                before,
                                catalog.book(id),
                                "上传封面");
                    });
        } catch (RuntimeException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        return ApiResponse.ok(
                new CoverResult("/api/v1/covers/" + filename), RequestIdFilter.id(request));
    }

    @GetMapping("/covers/{filename}")
    @Operation(summary = "读取封面文件（公开，不允许路径遍历或符号链接）")
    public ResponseEntity<Resource> image(@PathVariable String filename) {
        if (!filename.matches("[A-Za-z0-9._-]{1,100}\\.(jpg|png|webp)")) {
            throw BusinessException.notFound();
        }
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BusinessException.notFound();
        }
        MediaType type =
                filename.endsWith(".jpg")
                        ? MediaType.IMAGE_JPEG
                        : filename.endsWith(".png")
                                ? MediaType.IMAGE_PNG
                                : MediaType.parseMediaType("image/webp");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)))
                .body(new FileSystemResource(target));
    }
}
