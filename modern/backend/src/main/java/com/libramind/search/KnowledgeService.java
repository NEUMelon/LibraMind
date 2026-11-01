package com.libramind.search;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogChanged;
import com.libramind.catalog.CatalogDAO;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.AuditService;
import com.libramind.common.db.IdempotencyService;
import com.libramind.common.db.SqlStore;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/** 保存可引用知识与稳定片段，并在同一事务中登记向量生成或删除任务。 */
@Service
public class KnowledgeService {

    public record KnowledgeInput(
            Long editionId,
            @NotBlank @Size(max = 400) String title,
            @NotBlank @Size(max = 20000) String content,
            @NotBlank String documentType,
            @Size(max = 500) String source,
            @Size(max = 1000) String sourceUrl,
            @NotBlank String visibility) {}

    public record DocumentView(
            long id,
            Long editionId,
            String title,
            String content,
            String documentType,
            String source,
            String sourceUrl,
            String visibility,
            int version,
            boolean enabled) {}

    public record Citation(
            String id,
            long chunkId,
            long documentId,
            Long editionId,
            String title,
            String excerpt,
            String source,
            String sourceUrl,
            int version) {}

    private final SqlStore sql;
    private final CatalogDAO catalog;
    private final AuditService audit;

    public KnowledgeService(SqlStore sql, CatalogDAO catalog, AuditService audit) {
        this.sql = sql;
        this.catalog = catalog;
        this.audit = audit;
    }

    /** 馆藏元数据随业务提交同步变成知识材料，不生成任何不存在的简介或目录。 */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void catalogChanged(CatalogChanged event) {
        var book = catalog.findBook(event.editionId());
        var existing =
                sql.one(
                        "SELECT * FROM knowledge_document WHERE edition_id=? AND"
                                + " document_type='BOOK_METADATA' LIMIT 1 FOR UPDATE",
                        event.editionId());
        if (book == null) {
            if (existing != null) {
                disable(number(existing, "id"));
            }
            return;
        }
        String content =
                "书名："
                        + book.title()
                        + "\n作者："
                        + book.authors()
                        + "\n译者："
                        + book.translators()
                        + "\nISBN："
                        + book.isbn13()
                        + "\n出版社："
                        + book.publisher()
                        + "\n版本："
                        + book.editionStatement()
                        + "\n分类："
                        + book.category()
                        + "\n简介："
                        + book.description()
                        + "\n材料范围：这里只包含书目元数据及已提供的简介，不能据此断言章节或正文内容。";
        if (existing == null) {
            long id =
                    sql.insert(
                            """
INSERT INTO knowledge_document(edition_id,document_type,title,content,source,source_url)
VALUES(?,'BOOK_METADATA',?,?,?,?)
""",
                            book.id(),
                            book.title(),
                            content,
                            book.sourceType(),
                            book.sourceUrl());
            chunks(id, content);
        } else if (!text(existing, "content").equals(content) || !bool(existing, "enabled")) {
            long id = number(existing, "id");
            sql.update(
                    "UPDATE knowledge_document SET"
                        + " title=?,content=?,source=?,source_url=?,enabled=TRUE,version=version+1"
                        + " WHERE id=?",
                    book.title(),
                    content,
                    book.sourceType(),
                    book.sourceUrl(),
                    id);
            chunks(id, content);
        }
    }

    @Transactional
    public DocumentView create(CurrentUser actor, KnowledgeInput input) {
        ActorService.requireAdmin(actor);
        validate(input);
        long id =
                sql.insert(
                        """
INSERT INTO knowledge_document(edition_id,document_type,title,content,source,source_url,visibility)
VALUES(?,?,?,?,?,?,?)
""",
                        input.editionId(),
                        input.documentType(),
                        input.title(),
                        input.content(),
                        CatalogDAO.empty(input.source()),
                        CatalogDAO.empty(input.sourceUrl()),
                        input.visibility());
        chunks(id, input.content());
        DocumentView result = document(actor, id);
        audit.record(actor, "KNOWLEDGE_CREATE", "KNOWLEDGE", id, null, result, "新增可引用资料");
        return result;
    }

    @Transactional
    public DocumentView update(CurrentUser actor, long id, KnowledgeInput input) {
        ActorService.requireAdmin(actor);
        validate(input);
        DocumentView before = document(actor, id);
        if ("BOOK_METADATA".equals(before.documentType())) {
            throw BusinessException.invalid("自动书目资料应通过版本编辑接口更新");
        }
        sql.update(
                """
UPDATE knowledge_document SET edition_id=?,document_type=?,title=?,content=?,source=?,
source_url=?,visibility=?,enabled=TRUE,version=version+1 WHERE id=?
""",
                input.editionId(),
                input.documentType(),
                input.title(),
                input.content(),
                CatalogDAO.empty(input.source()),
                CatalogDAO.empty(input.sourceUrl()),
                input.visibility(),
                id);
        chunks(id, input.content());
        DocumentView result = document(actor, id);
        audit.record(actor, "KNOWLEDGE_UPDATE", "KNOWLEDGE", id, before, result, "更新资料版本");
        return result;
    }

    @Transactional
    public void delete(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        com.libramind.student.StudentService.requireReason(reason);
        DocumentView before = document(actor, id);
        disable(id);
        audit.record(actor, "KNOWLEDGE_DISABLE", "KNOWLEDGE", id, before, null, reason);
    }

    public DocumentView document(CurrentUser actor, long id) {
        var row = sql.one("SELECT * FROM knowledge_document WHERE id=? AND enabled=TRUE", id);
        if (row == null
                || (!"ADMIN".equals(actor.role()) && !"PUBLIC".equals(text(row, "visibility")))) {
            throw BusinessException.notFound();
        }
        return document(row);
    }

    public List<DocumentView> documents(CurrentUser actor, Long editionId) {
        ActorService.requireAdmin(actor);
        return sql
                .list(
                        "SELECT * FROM knowledge_document WHERE (? IS NULL OR edition_id=?) ORDER"
                                + " BY id DESC LIMIT 100",
                        editionId,
                        editionId)
                .stream()
                .map(this::document)
                .toList();
    }

    /** 只返回当前仍有效且用户可见的片段，用于引用校验和向量结果过滤。 */
    public Map<String, Object> validChunk(CurrentUser actor, long chunkId, String hash) {
        var row =
                sql.one(
                        """
SELECT c.*,d.edition_id,d.document_type,d.title,d.source,d.source_url,d.visibility,d.version
FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
LEFT JOIN book_edition e ON e.id=d.edition_id
WHERE c.id=? AND c.enabled=TRUE AND d.enabled=TRUE
AND (d.edition_id IS NULL OR e.archived=FALSE)
""",
                        chunkId);
        if (row == null
                || (hash != null && !hash.equals(text(row, "content_hash")))
                || (!"ADMIN".equals(actor.role()) && !"PUBLIC".equals(text(row, "visibility")))) {
            return null;
        }
        return row;
    }

    public Citation citation(Map<String, Object> row, String label) {
        return new Citation(
                label,
                number(row, "id"),
                number(row, "document_id"),
                row.get("edition_id") == null ? null : number(row, "edition_id"),
                text(row, "title"),
                text(row, "content"),
                text(row, "source"),
                text(row, "source_url"),
                (int) number(row, "version"));
    }

    private void chunks(long documentId, String content) {
        int index = 0;
        for (int start = 0; start < content.length(); start += 900) {
            String part = content.substring(start, Math.min(start + 1000, content.length()));
            String hash = IdempotencyService.digest(part);
            var row =
                    sql.one(
                            "SELECT id FROM knowledge_chunk WHERE document_id=? AND chunk_index=?",
                            documentId,
                            index);
            long chunkId;
            if (row == null) {
                chunkId =
                        sql.insert(
                                "INSERT INTO"
                                    + " knowledge_chunk(document_id,chunk_index,content,content_hash)"
                                    + " VALUES(?,?,?,?)",
                                documentId,
                                index,
                                part,
                                hash);
            } else {
                chunkId = number(row, "id");
                sql.update(
                        "UPDATE knowledge_chunk SET content=?,content_hash=?,enabled=TRUE WHERE"
                                + " id=?",
                        part,
                        hash,
                        chunkId);
            }
            task(chunkId, hash, "UPSERT");
            index++;
        }
        for (var old :
                sql.list(
                        "SELECT id,content_hash FROM knowledge_chunk WHERE document_id=? AND"
                                + " chunk_index>=?",
                        documentId,
                        index)) {
            sql.update("UPDATE knowledge_chunk SET enabled=FALSE WHERE id=?", old.get("id"));
            task(number(old, "id"), text(old, "content_hash"), "DELETE");
        }
    }

    private void disable(long id) {
        sql.update("UPDATE knowledge_document SET enabled=FALSE,version=version+1 WHERE id=?", id);
        for (var chunk :
                sql.list("SELECT id,content_hash FROM knowledge_chunk WHERE document_id=?", id)) {
            sql.update("UPDATE knowledge_chunk SET enabled=FALSE WHERE id=?", chunk.get("id"));
            task(number(chunk, "id"), text(chunk, "content_hash"), "DELETE");
        }
    }

    private void task(long chunkId, String hash, String operation) {
        sql.update(
                """
INSERT INTO index_task(chunk_id,operation,content_hash) VALUES(?,?,?)
ON DUPLICATE KEY UPDATE operation=VALUES(operation),content_hash=VALUES(content_hash),
status='PENDING',claim_token=NULL,lease_until=NULL,attempts=0,next_retry_at=UTC_TIMESTAMP(6),last_error=NULL
""",
                chunkId,
                operation,
                hash);
    }

    private void validate(KnowledgeInput input) {
        if (!List.of(
                                "DESCRIPTION",
                                "TABLE_OF_CONTENTS",
                                "PUBLIC_SUMMARY",
                                "MANUAL_NOTE",
                                "LIBRARY_POLICY")
                        .contains(input.documentType())
                || !List.of("PUBLIC", "ADMIN").contains(input.visibility())) {
            throw BusinessException.invalid("资料类型或可见范围不正确");
        }
        if (input.editionId() != null && catalog.findBook(input.editionId()) == null) {
            throw BusinessException.notFound();
        }
    }

    private DocumentView document(Map<String, Object> row) {
        return new DocumentView(
                number(row, "id"),
                row.get("edition_id") == null ? null : number(row, "edition_id"),
                text(row, "title"),
                text(row, "content"),
                text(row, "document_type"),
                text(row, "source"),
                text(row, "source_url"),
                text(row, "visibility"),
                (int) number(row, "version"),
                bool(row, "enabled"));
    }
}
