package com.libramind.catalog;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.AuditService;
import com.libramind.common.db.SqlStore;
import com.libramind.student.StudentService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 馆藏维护业务，人工作品分组、版本副本变更与历史删除均在事务中审计。 */
@Service
public class CatalogService {

    private final CatalogDAO catalog;
    private final SqlStore sql;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CatalogService(
            CatalogDAO catalog,
            SqlStore sql,
            AuditService audit,
            ApplicationEventPublisher events) {
        this.catalog = catalog;
        this.sql = sql;
        this.audit = audit;
        this.events = events;
    }

    public BookView book(long id) {
        BookView result = catalog.findBook(id);
        if (result == null) {
            throw BusinessException.notFound();
        }
        return result;
    }

    public CopyView copy(long id) {
        CopyView result = catalog.findCopy(id);
        if (result == null) {
            throw BusinessException.notFound();
        }
        return result;
    }

    @Transactional
    public BookView createBook(CurrentUser actor, BookInput input) {
        ActorService.requireAdmin(actor);
        long workId =
                input.workId() == null
                        ? sql.insert(
                                "INSERT INTO work(display_title,display_creators) VALUES(?,?)",
                                input.title(),
                                input.authors())
                        : input.workId();
        checkGroup(workId, input, null);
        long id = catalog.insertBook(input, workId);
        BookView result = book(id);
        audit.record(actor, "BOOK_CREATE", "EDITION", id, null, result, "新增版本");
        events.publishEvent(new CatalogChanged(id));
        return result;
    }

    @Transactional
    public BookView updateBook(CurrentUser actor, long id, BookInput input) {
        ActorService.requireAdmin(actor);
        lockEdition(id);
        BookView before = book(id);
        if (input.workId() != null && input.workId() != before.workId()) {
            throw BusinessException.invalid("调整作品分组请使用作品关联接口，并填写原因");
        }
        checkGroup(before.workId(), input, id);
        catalog.updateBook(id, input);
        BookView after = book(id);
        audit.record(actor, "BOOK_UPDATE", "EDITION", id, before, after, "修改版本资料");
        events.publishEvent(new CatalogChanged(id));
        return after;
    }

    @Transactional
    public String deleteBook(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        StudentService.requireReason(reason);
        lockEdition(id);
        BookView before = book(id);
        if (sql.count("SELECT COUNT(*) FROM book_copy WHERE edition_id=?", id) > 0) {
            throw BusinessException.conflict("EDITION_HAS_COPIES", "版本仍有馆藏副本，不能删除");
        }
        sql.update("UPDATE book_edition SET archived=TRUE WHERE id=?", id);
        audit.record(actor, "BOOK_ARCHIVE", "EDITION", id, before, null, reason);
        events.publishEvent(new CatalogChanged(id));
        return "ARCHIVED";
    }

    @Transactional
    public WorkView assignWork(CurrentUser actor, long editionId, WorkAssignment input) {
        ActorService.requireAdmin(actor);
        lockEdition(editionId);
        BookView before = book(editionId);
        long workId =
                input.workId() == null
                        ? sql.insert(
                                "INSERT INTO work(display_title,display_creators) VALUES(?,?)",
                                before.title(),
                                before.authors())
                        : input.workId();
        WorkView work = catalog.findWork(workId);
        if (work == null) {
            throw BusinessException.notFound();
        }
        for (BookView member : work.editions()) {
            if (!member.volume().equals(before.volume())
                    || !member.language().equals(before.language())) {
                throw BusinessException.invalid("首版只允许同语言、同卷次的版本加入同一分组");
            }
        }
        sql.update("UPDATE book_edition SET work_id=? WHERE id=?", workId, editionId);
        audit.record(
                actor,
                "WORK_ASSIGN",
                "EDITION",
                editionId,
                before,
                book(editionId),
                input.reason());
        return catalog.findWork(workId);
    }

    @Transactional
    public CopyView createCopy(CurrentUser actor, CopyInput input) {
        ActorService.requireAdmin(actor);
        lockEdition(input.editionId());
        book(input.editionId());
        if (input.circulationStatus() == CopyStatus.BORROWED) {
            throw BusinessException.invalid("新增副本不能直接设置为在借，请办理借阅");
        }
        long id =
                sql.insert(
                        """
INSERT INTO book_copy(accession_no,edition_id,call_number,location,circulation_status)
VALUES(?,?,?,?,?)
""",
                        input.accessionNo(),
                        input.editionId(),
                        CatalogDAO.empty(input.callNumber()),
                        CatalogDAO.empty(input.location()),
                        input.circulationStatus().name());
        CopyView result = copy(id);
        audit.record(actor, "COPY_CREATE", "COPY", id, null, result, "新增馆藏");
        return result;
    }

    @Transactional
    public CopyView updateCopy(CurrentUser actor, long id, CopyInput input) {
        ActorService.requireAdmin(actor);
        lockCopy(id);
        CopyView before = copy(id);
        if (input.editionId() != before.editionId()) {
            throw BusinessException.invalid("馆藏副本不能直接更换所属版本，请删除无关联副本后重新登记");
        }
        if ((before.circulationStatus() == CopyStatus.BORROWED)
                != (input.circulationStatus() == CopyStatus.BORROWED)) {
            throw BusinessException.conflict("COPY_STATE_CONTROLLED", "在借状态只能由借还业务改变");
        }
        sql.update(
                "UPDATE book_copy SET accession_no=?,call_number=?,location=?,circulation_status=?"
                        + " WHERE id=?",
                input.accessionNo(),
                CatalogDAO.empty(input.callNumber()),
                CatalogDAO.empty(input.location()),
                input.circulationStatus().name(),
                id);
        CopyView after = copy(id);
        audit.record(actor, "COPY_UPDATE", "COPY", id, before, after, "修改馆藏");
        return after;
    }

    @Transactional
    public String deleteCopy(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        StudentService.requireReason(reason);
        lockCopy(id);
        CopyView before = copy(id);
        if (before.circulationStatus() == CopyStatus.BORROWED) {
            throw BusinessException.conflict("COPY_IS_BORROWED", "在借副本不能删除");
        }
        boolean keep = sql.count("SELECT COUNT(*) FROM loan WHERE copy_id=?", id) > 0;
        if (keep) {
            sql.update("UPDATE book_copy SET circulation_status='OFF_SHELF' WHERE id=?", id);
        } else {
            sql.update("DELETE FROM book_copy WHERE id=?", id);
        }
        audit.record(
                actor,
                keep ? "COPY_OFF_SHELF" : "COPY_DELETE",
                "COPY",
                id,
                before,
                keep ? copy(id) : null,
                reason);
        return keep ? "OFF_SHELF" : "DELETED";
    }

    private void checkGroup(long id, BookInput input, Long excludedEdition) {
        if (sql.one("SELECT id FROM work WHERE id=? FOR UPDATE", id) == null) {
            throw BusinessException.notFound();
        }
        String language = CatalogDAO.empty(input.language()).isBlank() ? "zh" : input.language();
        for (BookView member : catalog.editions(id)) {
            if (excludedEdition != null && member.id() == excludedEdition) {
                continue;
            }
            if (!member.volume().equals(CatalogDAO.empty(input.volume()))
                    || !member.language().equals(language)) {
                throw BusinessException.invalid("作品分组必须保持相同语言和卷次");
            }
        }
    }

    private void lockEdition(long id) {
        if (sql.one("SELECT id FROM book_edition WHERE id=? AND archived=FALSE FOR UPDATE", id)
                == null) {
            throw BusinessException.notFound();
        }
    }

    private void lockCopy(long id) {
        if (sql.one("SELECT id FROM book_copy WHERE id=? AND archived=FALSE FOR UPDATE", id)
                == null) {
            throw BusinessException.notFound();
        }
    }
}
