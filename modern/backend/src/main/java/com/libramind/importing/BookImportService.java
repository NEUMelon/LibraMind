package com.libramind.importing;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.catalog.CatalogService;
import com.libramind.catalog.Isbn;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.IdempotencyService;
import com.libramind.common.db.SqlStore;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 真实书目导入，预览不写库，执行按 ISBN 更新版本并确定性生成副本号。 */
@Service
public class BookImportService {

    public record ImportInput(List<BookInput> books, int copiesPerEdition) {}

    public record ImportError(int row, String message) {}

    public record ImportPreview(
            int total, int newEditions, int existingEditions, List<ImportError> errors) {}

    public record ImportResult(int created, int updated, int copiesCreated) {}

    private final CatalogDAO catalog;
    private final CatalogService service;
    private final Validator validator;
    private final SqlStore sql;
    private final IdempotencyService idempotency;

    public BookImportService(
            CatalogDAO catalog,
            CatalogService service,
            Validator validator,
            SqlStore sql,
            IdempotencyService idempotency) {
        this.catalog = catalog;
        this.service = service;
        this.validator = validator;
        this.sql = sql;
        this.idempotency = idempotency;
    }

    public ImportPreview preview(CurrentUser actor, ImportInput input) {
        ActorService.requireAdmin(actor);
        if (input.books() == null
                || input.books().isEmpty()
                || input.books().size() > 500
                || input.copiesPerEdition() < 0
                || input.copiesPerEdition() > 5) {
            throw BusinessException.invalid("一次导入1到500条书目，每个版本新增0到5个副本");
        }
        List<ImportError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int additions = 0;
        int existing = 0;
        for (int i = 0; i < input.books().size(); i++) {
            BookInput book = input.books().get(i);
            try {
                if (book == null || !validator.validate(book).isEmpty()) {
                    throw BusinessException.invalid("书目必填字段缺失或超过长度限制");
                }
                String isbn = Isbn.normalize(book.isbn13());
                if (isbn == null || !seen.add(isbn)) {
                    throw BusinessException.invalid("导入书目必须提供有效且批内不重复的ISBN");
                }
                if (catalog.findIsbn(isbn) == null) {
                    additions++;
                } else {
                    existing++;
                }
            } catch (BusinessException exception) {
                errors.add(new ImportError(i + 1, exception.getMessage()));
            }
        }
        return new ImportPreview(input.books().size(), additions, existing, errors);
    }

    @Transactional
    public ImportResult execute(CurrentUser actor, ImportInput input, String key) {
        ActorService.requireAdmin(actor);
        return idempotency.execute(
                actor,
                key,
                "IMPORT_BOOKS",
                input,
                ImportResult.class,
                () -> {
                    ImportPreview preview = preview(actor, input);
                    if (!preview.errors().isEmpty()) {
                        throw BusinessException.invalid("导入预检失败，请先查看预览中的行号和错误");
                    }
                    int created = 0;
                    int updated = 0;
                    int copies = 0;
                    for (BookInput book : input.books()) {
                        BookView existing = catalog.findIsbn(Isbn.normalize(book.isbn13()));
                        BookView saved;
                        if (existing == null) {
                            saved = service.createBook(actor, book);
                            created++;
                        } else {
                            saved = service.updateBook(actor, existing.id(), book);
                            updated++;
                        }
                        sql.update(
                                "UPDATE book_edition SET source_type='IMPORT',source_record_id=?"
                                        + " WHERE id=?",
                                saved.isbn13(),
                                saved.id());
                        for (int n = 1; n <= input.copiesPerEdition(); n++) {
                            String accession =
                                    "LM-" + saved.isbn13() + "-" + String.format("%02d", n);
                            if (catalog.findAccession(accession) == null) {
                                service.createCopy(
                                        actor,
                                        new CopyInput(
                                                accession,
                                                saved.id(),
                                                saved.classificationNo(),
                                                "模拟馆藏·二层综合书库",
                                                CopyStatus.AVAILABLE));
                                copies++;
                            }
                        }
                    }
                    return new ImportResult(created, updated, copies);
                });
    }
}
