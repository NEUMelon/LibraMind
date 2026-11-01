package com.libramind.catalog;

import static org.assertj.core.api.Assertions.*;

import com.libramind.IntegrationSupport;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.common.api.BusinessException;
import com.libramind.importing.BookImportService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

class CatalogAndImportIT extends IntegrationSupport {
    @Autowired BookImportService importing;

    @Test
    void groupingUsesExplicitWorkAndRejectsDifferentLanguageOrVolume() {
        var first = book("作品分组测试");
        var second = catalog.createBook(admin, input(first.workId(), "另一个版本", "zh", ""));
        assertThat(second.workId()).isEqualTo(first.workId());
        assertThatThrownBy(() -> catalog.createBook(admin, input(first.workId(), "英文版", "en", "")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.createBook(admin, input(first.workId(), "第二卷", "zh", "2")))
                .isInstanceOf(BusinessException.class);
        catalog.assignWork(admin, second.id(), new WorkAssignment(null, "移出测试分组"));
        var changed = catalog.updateBook(admin, second.id(), input(null, "独立版本", "en", "2"));
        assertThat(changed.language()).isEqualTo("en");
    }

    @Test
    void importPreviewDoesNotWriteAndBatchDuplicateFailsAtomically() {
        long before = sql.count("SELECT COUNT(*) FROM book_edition");
        var invalid =
                new BookImportService.ImportInput(List.of(input(null, "没有ISBN", "zh", "")), 2);
        assertThat(importing.preview(admin, invalid).errors()).hasSize(1);
        assertThatThrownBy(() -> importing.execute(admin, invalid, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(sql.count("SELECT COUNT(*) FROM book_edition")).isEqualTo(before);
    }

    @Test
    void readerCannotWriteCatalogAndBorrowedStatusIsBusinessControlled() {
        var reader = reader();
        var book = book("权限测试书");
        assertThatThrownBy(() -> catalog.createBook(reader, input(null, "越权", "zh", "")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                catalog.createCopy(
                                        admin,
                                        new CopyInput(
                                                "IT-" + UUID.randomUUID(),
                                                book.id(),
                                                "",
                                                "",
                                                CopyStatus.BORROWED)))
                .isInstanceOf(BusinessException.class);
    }

    private BookInput input(Long workId, String title, String language, String volume) {
        return new BookInput(
                workId, title, "", "测试作者", "", null, "测试出版社", "2026", "第2版", volume, language, "",
                "", "", "");
    }
}
