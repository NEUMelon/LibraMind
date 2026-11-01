package com.libramind.catalog;

import jakarta.validation.constraints.*;

import java.util.List;

/** 作品、出版版本与实体馆藏副本的数据对象，三种标识互不混用。 */
public final class CatalogDtos {
    private CatalogDtos() {}

    public enum CopyStatus {
        AVAILABLE,
        BORROWED,
        MAINTENANCE,
        OFF_SHELF
    }

    public record BookInput(
            Long workId,
            @NotBlank @Size(max = 400) String title,
            @Size(max = 400) String subtitle,
            @NotBlank @Size(max = 500) String authors,
            @Size(max = 500) String translators,
            String isbn13,
            @Size(max = 200) String publisher,
            @Size(max = 80) String publicationDate,
            @Size(max = 100) String editionStatement,
            @Size(max = 80) String volume,
            @Size(max = 32) String language,
            @Size(max = 80) String classificationNo,
            @Size(max = 100) String category,
            @Size(max = 20000) String description,
            @Size(max = 1000) String sourceUrl) {}

    public record BookView(
            long id,
            long workId,
            String title,
            String subtitle,
            String authors,
            String translators,
            String isbn13,
            String publisher,
            String publicationDate,
            String editionStatement,
            String volume,
            String language,
            String classificationNo,
            String category,
            String description,
            String coverUrl,
            String sourceType,
            String sourceRecordId,
            String sourceUrl,
            long copyCount,
            long availableCount,
            boolean archived) {}

    public record CopyInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String accessionNo,
            @Positive long editionId,
            @Size(max = 100) String callNumber,
            @Size(max = 200) String location,
            @NotNull CopyStatus circulationStatus) {}

    public record CopyView(
            long id,
            String accessionNo,
            long editionId,
            String title,
            String authors,
            String editionStatement,
            String isbn13,
            String callNumber,
            String location,
            CopyStatus circulationStatus,
            boolean archived) {}

    public record WorkView(
            long id,
            String displayTitle,
            String displayCreators,
            List<BookView> editions,
            long copyCount,
            long availableCount) {}

    public record WorkAssignment(Long workId, @NotBlank @Size(max = 500) String reason) {}
}
