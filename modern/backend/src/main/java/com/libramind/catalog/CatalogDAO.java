package com.libramind.catalog;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.catalog.CatalogDtos.*;
import com.libramind.common.api.BusinessException;
import com.libramind.common.api.PageResult;
import com.libramind.common.db.SqlStore;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 馆藏数据访问，分页结果联合展示作品、版本、副本及实时可借数量。 */
@Repository
public class CatalogDAO {

    private static final String BOOK_SELECT =
            """
SELECT e.*, (SELECT COUNT(*) FROM book_copy c WHERE c.edition_id=e.id AND c.archived=FALSE) AS copy_count,
(SELECT COUNT(*) FROM book_copy c WHERE c.edition_id=e.id AND c.archived=FALSE
    AND c.circulation_status='AVAILABLE') AS available_count FROM book_edition e
""";
    private static final String COPY_SELECT =
            """
            SELECT c.*,e.title,e.authors,e.edition_statement,e.isbn13 FROM book_copy c
            JOIN book_edition e ON e.id=c.edition_id
            """;
    private final SqlStore sql;

    public CatalogDAO(SqlStore sql) {
        this.sql = sql;
    }

    public PageResult<BookView> books(String q, boolean available, int page, int size) {
        return books(q, available, page, size, "newest");
    }

    public PageResult<BookView> books(
            String q, boolean available, int page, int size, String sort) {
        PageResult.validate(page, size);
        String order =
                switch (sort) {
                    case "newest" -> "e.id DESC";
                    case "title" -> "e.title,e.id";
                    case "available" -> "available_count DESC,e.id";
                    default -> throw BusinessException.invalid("sort只支持newest、title、available");
                };
        String where = " WHERE e.archived=FALSE";
        List<Object> params = new ArrayList<>();
        if (!q.isBlank()) {
            String like = "%" + q + "%";
            where +=
                    " AND (e.title LIKE ? OR e.authors LIKE ? OR e.isbn13=? OR e.publisher LIKE ?"
                            + " OR e.category LIKE ?)";
            params.addAll(List.of(like, like, q.replace("-", ""), like, like));
        }
        if (available) {
            where +=
                    " AND EXISTS(SELECT 1 FROM book_copy c WHERE c.edition_id=e.id AND"
                            + " c.archived=FALSE AND c.circulation_status='AVAILABLE')";
        }
        long count = sql.count("SELECT COUNT(*) FROM book_edition e" + where, params.toArray());
        params.add(size);
        params.add((page - 1L) * size);
        return PageResult.of(
                sql
                        .list(
                                BOOK_SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                                params.toArray())
                        .stream()
                        .map(this::book)
                        .toList(),
                page,
                size,
                count);
    }

    public BookView findBook(long id) {
        Map<String, Object> row = sql.one(BOOK_SELECT + " WHERE e.id=? AND e.archived=FALSE", id);
        return row == null ? null : book(row);
    }

    public BookView findIsbn(String isbn) {
        Map<String, Object> row =
                sql.one(BOOK_SELECT + " WHERE e.isbn13=? AND e.archived=FALSE", isbn);
        return row == null ? null : book(row);
    }

    public List<BookView> editions(long workId) {
        return sql
                .list(BOOK_SELECT + " WHERE e.work_id=? AND e.archived=FALSE ORDER BY e.id", workId)
                .stream()
                .map(this::book)
                .toList();
    }

    public WorkView findWork(long id) {
        Map<String, Object> row = sql.one("SELECT * FROM work WHERE id=?", id);
        if (row == null) {
            return null;
        }
        List<BookView> editions = editions(id);
        return new WorkView(
                id,
                text(row, "display_title"),
                text(row, "display_creators"),
                editions,
                editions.stream().mapToLong(BookView::copyCount).sum(),
                editions.stream().mapToLong(BookView::availableCount).sum());
    }

    public PageResult<CopyView> copies(
            String q, CopyStatus status, Long editionId, int page, int size) {
        PageResult.validate(page, size);
        String where = " WHERE c.archived=FALSE AND e.archived=FALSE";
        List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            where +=
                    " AND (c.accession_no LIKE ? OR e.title LIKE ? OR e.authors LIKE ? OR"
                            + " e.isbn13=?)";
            args.addAll(List.of("%" + q + "%", "%" + q + "%", "%" + q + "%", q));
        }
        if (status != null) {
            where += " AND c.circulation_status=?";
            args.add(status.name());
        }
        if (editionId != null) {
            where += " AND c.edition_id=?";
            args.add(editionId);
        }
        long total =
                sql.count(
                        "SELECT COUNT(*) FROM book_copy c JOIN book_edition e ON e.id=c.edition_id"
                                + where,
                        args.toArray());
        args.add(size);
        args.add((page - 1L) * size);
        return PageResult.of(
                sql
                        .list(
                                COPY_SELECT + where + " ORDER BY c.accession_no LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::copy)
                        .toList(),
                page,
                size,
                total);
    }

    public CopyView findCopy(long id) {
        Map<String, Object> row = sql.one(COPY_SELECT + " WHERE c.id=? AND c.archived=FALSE", id);
        return row == null ? null : copy(row);
    }

    public CopyView findAccession(String number) {
        Map<String, Object> row =
                sql.one(COPY_SELECT + " WHERE c.accession_no=? AND c.archived=FALSE", number);
        return row == null ? null : copy(row);
    }

    public long insertBook(BookInput input, long workId) {
        return sql.insert(
                """
INSERT INTO book_edition(work_id,title,subtitle,authors,translators,isbn13,publisher,
    publication_date,edition_statement,volume,language,classification_no,category,description,source_url)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
                workId,
                input.title(),
                empty(input.subtitle()),
                input.authors(),
                empty(input.translators()),
                Isbn.normalize(input.isbn13()),
                empty(input.publisher()),
                empty(input.publicationDate()),
                empty(input.editionStatement()),
                empty(input.volume()),
                language(input.language()),
                empty(input.classificationNo()),
                empty(input.category()),
                empty(input.description()),
                empty(input.sourceUrl()));
    }

    public void updateBook(long id, BookInput input) {
        sql.update(
                """
UPDATE book_edition SET title=?,subtitle=?,authors=?,translators=?,isbn13=?,publisher=?,
publication_date=?,edition_statement=?,volume=?,language=?,classification_no=?,category=?,description=?,source_url=?
WHERE id=?
""",
                input.title(),
                empty(input.subtitle()),
                input.authors(),
                empty(input.translators()),
                Isbn.normalize(input.isbn13()),
                empty(input.publisher()),
                empty(input.publicationDate()),
                empty(input.editionStatement()),
                empty(input.volume()),
                language(input.language()),
                empty(input.classificationNo()),
                empty(input.category()),
                empty(input.description()),
                empty(input.sourceUrl()),
                id);
    }

    private BookView book(Map<String, Object> row) {
        String cover = text(row, "cover_path");
        return new BookView(
                number(row, "id"),
                number(row, "work_id"),
                text(row, "title"),
                text(row, "subtitle"),
                text(row, "authors"),
                text(row, "translators"),
                text(row, "isbn13"),
                text(row, "publisher"),
                text(row, "publication_date"),
                text(row, "edition_statement"),
                text(row, "volume"),
                text(row, "language"),
                text(row, "classification_no"),
                text(row, "category"),
                text(row, "description"),
                cover.isBlank() ? "/assets/cover-missing.svg" : "/api/v1/covers/" + cover,
                text(row, "source_type"),
                text(row, "source_record_id"),
                text(row, "source_url"),
                number(row, "copy_count"),
                number(row, "available_count"),
                bool(row, "archived"));
    }

    private CopyView copy(Map<String, Object> row) {
        return new CopyView(
                number(row, "id"),
                text(row, "accession_no"),
                number(row, "edition_id"),
                text(row, "title"),
                text(row, "authors"),
                text(row, "edition_statement"),
                text(row, "isbn13"),
                text(row, "call_number"),
                text(row, "location"),
                CopyStatus.valueOf(text(row, "circulation_status")),
                bool(row, "archived"));
    }

    public static String empty(String value) {
        return value == null ? "" : value.trim();
    }

    private String language(String value) {
        return empty(value).isBlank() ? "zh" : value.trim();
    }
}
