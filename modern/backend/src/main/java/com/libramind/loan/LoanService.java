package com.libramind.loan;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.CopyView;
import com.libramind.common.api.BusinessException;
import com.libramind.common.api.PageResult;
import com.libramind.common.db.AuditService;
import com.libramind.common.db.IdempotencyService;
import com.libramind.common.db.SqlStore;
import com.libramind.loan.LoanDtos.*;
import com.libramind.student.StudentDAO;
import com.libramind.student.StudentService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 流通业务服务，统一执行借还续借、历史维护、权限、事务和审计。 */
@Service
public class LoanService {

    private final CirculationRules rules;
    private final SqlStore sql;
    private final LoanDAO loans;
    private final StudentDAO students;
    private final CatalogDAO catalog;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public LoanService(
            SqlStore sql,
            LoanDAO loans,
            StudentDAO students,
            CatalogDAO catalog,
            AuditService audit,
            IdempotencyService idempotency,
            CirculationRules rules) {
        this.rules = rules;
        this.sql = sql;
        this.loans = loans;
        this.students = students;
        this.catalog = catalog;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    public LoanView find(CurrentUser actor, long id) {
        LoanView found = loans.find(id);
        if (found == null) {
            throw BusinessException.notFound();
        }
        ActorService.requireOwner(actor, found.studentId());
        return found;
    }

    public PageResult<LoanView> list(
            CurrentUser actor,
            Long studentId,
            String q,
            LoanStatus status,
            boolean returns,
            Instant from,
            Instant to,
            int page,
            int size) {
        if (!"ADMIN".equals(actor.role())) {
            if (studentId != null) {
                ActorService.requireOwner(actor, studentId);
            }
            studentId = actor.studentId();
            if (studentId == null) {
                throw BusinessException.notFound();
            }
        }
        return loans.list(studentId, q, status, returns, from, to, page, size);
    }

    public Eligibility eligibility(CurrentUser actor, Long requestedStudent) {
        long studentId = studentId(actor, requestedStudent);
        var student = students.find(studentId);
        if (student == null) {
            throw BusinessException.notFound();
        }
        long overdue =
                sql.count(
                        "SELECT COUNT(*) FROM loan WHERE student_id=? AND status='ACTIVE' AND"
                                + " due_at<UTC_TIMESTAMP(6)",
                        studentId);
        int active = (int) student.currentBorrowedCount();
        return new Eligibility(
                studentId,
                student.enabled(),
                active,
                rules.maxActive(),
                Math.max(0, rules.maxActive() - active),
                overdue,
                student.enabled() && active < rules.maxActive());
    }

    /** 逐册预检并显示额度，不锁定或修改库存；正式提交仍重新执行全部检查。 */
    public BorrowPreview preview(CurrentUser actor, BorrowInput input) {
        var eligibility = eligibility(actor, input.studentId());
        var seen = new HashSet<String>();
        List<CopyCheck> checks = new ArrayList<>();
        for (String number : input.accessionNos()) {
            var copy = catalog.findAccession(number);
            boolean available =
                    copy != null
                            && !copy.archived()
                            && copy.circulationStatus()
                                    == com.libramind.catalog.CatalogDtos.CopyStatus.AVAILABLE
                            && seen.add(number);
            checks.add(new CopyCheck(number, copy, available, available ? "可借" : "不存在、不可借或批内重复"));
        }
        return new BorrowPreview(
                eligibility,
                checks,
                eligibility.canBorrow()
                        && eligibility.remaining() >= checks.size()
                        && checks.stream().allMatch(CopyCheck::available));
    }

    @Transactional
    public BorrowResult borrow(CurrentUser actor, BorrowInput input, String key) {
        return idempotency.execute(
                actor, key, "BORROW", input, BorrowResult.class, () -> doBorrow(actor, input));
    }

    private BorrowResult doBorrow(CurrentUser actor, BorrowInput input) {
        long studentId = studentId(actor, input.studentId());
        Map<String, Object> student = lockStudent(studentId);
        if (!bool(student, "enabled")) {
            throw BusinessException.conflict("STUDENT_DISABLED", "学生已停用，不能新增借阅");
        }
        if (input.accessionNos() == null
                || input.accessionNos().isEmpty()
                || input.accessionNos().size() > 10
                || new HashSet<>(input.accessionNos()).size() != input.accessionNos().size()) {
            throw BusinessException.invalid("请提供1到10个不重复的馆藏登录号");
        }
        List<CopyView> copies =
                input.accessionNos().stream()
                        .map(
                                number -> {
                                    CopyView copy = catalog.findAccession(number);
                                    if (copy == null) {
                                        throw BusinessException.notFound();
                                    }
                                    return copy;
                                })
                        .sorted(Comparator.comparingLong(CopyView::id))
                        .toList();
        List<Map<String, Object>> locked =
                copies.stream().map(copy -> lockCopy(copy.id())).toList();
        if (loans.activeCountForUpdate(studentId) + copies.size() > rules.maxActive()) {
            throw BusinessException.conflict(
                    "BORROW_LIMIT_EXCEEDED", "同时在借数量不能超过" + rules.maxActive() + "册");
        }
        for (int i = 0; i < copies.size(); i++) {
            Map<String, Object> copy = locked.get(i);
            if (!"AVAILABLE".equals(text(copy, "circulation_status"))
                    || bool(copy, "archived")
                    || !copies.get(i).accessionNo().equals(text(copy, "accession_no"))) {
                throw BusinessException.conflict("COPY_UNAVAILABLE", "本批次存在不可借或已变更的副本，请重新查询");
            }
        }
        Instant now = Instant.now();
        List<LoanView> created = new ArrayList<>();
        for (CopyView copy : copies) {
            if (sql.update(
                            "UPDATE book_copy SET circulation_status='BORROWED' WHERE id=? AND"
                                    + " circulation_status='AVAILABLE' AND archived=FALSE",
                            copy.id())
                    != 1) {
                throw BusinessException.conflict("COPY_UNAVAILABLE", "副本已被其他操作占用");
            }
            long loanId =
                    sql.insert(
                            """
INSERT INTO loan(student_id,copy_id,borrowed_at,due_at,operator_id,note)
VALUES(?,?,?,?,?,?)
""",
                            studentId,
                            copy.id(),
                            now,
                            now.plus(rules.loanDays(), ChronoUnit.DAYS),
                            actor.id(),
                            CatalogDAO.empty(input.note()));
            LoanView result = loans.find(loanId);
            audit.record(actor, "BORROW", "LOAN", loanId, null, result, "登记借阅");
            created.add(result);
        }
        return new BorrowResult(created);
    }

    @Transactional
    public LoanView giveBack(CurrentUser actor, ReturnInput input, String key) {
        return idempotency.execute(
                actor,
                key,
                "RETURN",
                input,
                LoanView.class,
                () -> {
                    Long loanId = input.loanId();
                    if (loanId == null) {
                        if (input.accessionNo() == null || input.accessionNo().isBlank()) {
                            throw BusinessException.invalid("请提供借阅编号或馆藏登录号");
                        }
                        Map<String, Object> active =
                                sql.one(
                                        """
                                        SELECT l.id FROM loan l JOIN book_copy c ON c.id=l.copy_id
                                        WHERE c.accession_no=? AND l.status='ACTIVE'
                                        """,
                                        input.accessionNo());
                        if (active == null) {
                            throw BusinessException.conflict("NO_ACTIVE_LOAN", "该副本没有在借记录");
                        }
                        loanId = number(active, "id");
                    }
                    LoanView before = lockLoan(actor, loanId);
                    if (before.status() != LoanStatus.ACTIVE) {
                        throw BusinessException.conflict("LOAN_NOT_ACTIVE", "该记录不是在借状态");
                    }
                    Instant now = Instant.now();
                    sql.update(
                            "UPDATE loan SET"
                                + " status='RETURNED',returned_at=?,version=version+1,note=? WHERE"
                                + " id=?",
                            now,
                            input.note() == null ? before.note() : input.note(),
                            loanId);
                    sql.update(
                            "UPDATE book_copy SET circulation_status='AVAILABLE' WHERE id=?",
                            before.copyId());
                    LoanView result = loans.find(loanId);
                    audit.record(actor, "RETURN", "LOAN", loanId, before, result, "登记归还");
                    return result;
                });
    }

    @Transactional
    public LoanView renew(CurrentUser actor, long id, String key) {
        return idempotency.execute(
                actor,
                key,
                "RENEW",
                id,
                LoanView.class,
                () -> {
                    LoanView before = lockLoan(actor, id);
                    if (before.status() != LoanStatus.ACTIVE) {
                        throw BusinessException.conflict("LOAN_NOT_ACTIVE", "只有在借记录可以续借");
                    }
                    if (before.dueAt().isBefore(Instant.now())) {
                        throw BusinessException.conflict("LOAN_OVERDUE", "已逾期的借阅不能续借");
                    }
                    if (before.renewCount() >= rules.maxRenewals()) {
                        throw BusinessException.conflict("RENEW_LIMIT_REACHED", "已达到配置的续借次数上限");
                    }
                    sql.update(
                            "UPDATE loan SET due_at=?,renew_count=renew_count+1,version=version+1"
                                    + " WHERE id=?",
                            before.dueAt().plus(rules.renewalDays(), ChronoUnit.DAYS),
                            id);
                    LoanView result = loans.find(id);
                    audit.record(
                            actor,
                            "RENEW",
                            "LOAN",
                            id,
                            before,
                            result,
                            "续借" + rules.renewalDays() + "天");
                    return result;
                });
    }

    @Transactional
    public LoanView cancel(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        StudentService.requireReason(reason);
        LoanView before = lockLoan(actor, id);
        if (before.status() == LoanStatus.CANCELLED) {
            throw BusinessException.conflict("LOAN_CANCELLED", "该记录已作废");
        }
        sql.update(
                "UPDATE loan SET"
                    + " status='CANCELLED',cancelled_at=?,cancellation_reason=?,version=version+1"
                    + " WHERE id=?",
                Instant.now(),
                reason,
                id);
        if (before.status() == LoanStatus.ACTIVE) {
            sql.update(
                    "UPDATE book_copy SET circulation_status='AVAILABLE' WHERE id=?",
                    before.copyId());
        }
        LoanView result = loans.find(id);
        audit.record(actor, "CANCEL_LOAN", "LOAN", id, before, result, reason);
        return result;
    }

    @Transactional
    public LoanView undoReturn(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        StudentService.requireReason(reason);
        LoanView before = lockLoan(actor, id);
        if (before.status() != LoanStatus.RETURNED) {
            throw BusinessException.conflict("LOAN_NOT_RETURNED", "只能撤销已归还记录");
        }
        Map<String, Object> copy = lockCopy(before.copyId());
        if (!"AVAILABLE".equals(text(copy, "circulation_status"))
                || bool(copy, "archived")
                || !sql.list(
                                "SELECT id FROM loan WHERE copy_id=? AND status<>'CANCELLED' AND"
                                        + " id>? FOR UPDATE",
                                before.copyId(),
                                id)
                        .isEmpty()) {
            throw BusinessException.conflict("LATER_CIRCULATION_EXISTS", "副本已有后续流通或当前状态不允许恢复");
        }
        if (loans.activeCountForUpdate(before.studentId()) >= rules.maxActive()) {
            throw BusinessException.conflict("BORROW_LIMIT_EXCEEDED", "恢复借阅会超过借阅上限");
        }
        sql.update(
                "UPDATE loan SET status='ACTIVE',returned_at=NULL,version=version+1 WHERE id=?",
                id);
        sql.update(
                "UPDATE book_copy SET circulation_status='BORROWED' WHERE id=?", before.copyId());
        LoanView result = loans.find(id);
        audit.record(actor, "UNDO_RETURN", "LOAN", id, before, result, reason);
        return result;
    }

    @Transactional
    public LoanView correct(CurrentUser actor, long id, CorrectionInput input, boolean returnOnly) {
        ActorService.requireAdmin(actor);
        StudentService.requireReason(input.reason());
        LoanView before = lockLoan(actor, id);
        if (before.status() == LoanStatus.CANCELLED
                || (returnOnly && before.status() != LoanStatus.RETURNED)) {
            throw BusinessException.conflict("LOAN_STATE_INVALID", "该状态不能进行日期更正");
        }
        Instant borrowed = input.borrowedAt() == null ? before.borrowedAt() : input.borrowedAt();
        Instant due = input.dueAt() == null ? before.dueAt() : input.dueAt();
        Instant returned = input.returnedAt() == null ? before.returnedAt() : input.returnedAt();
        if (returnOnly && (!borrowed.equals(before.borrowedAt()) || !due.equals(before.dueAt()))) {
            throw BusinessException.invalid("还书更正接口不能修改借阅日期和应还日期");
        }
        if (borrowed.isAfter(Instant.now())
                || due.isBefore(borrowed)
                || (returned != null
                        && (returned.isBefore(borrowed) || returned.isAfter(Instant.now())))
                || (before.status() == LoanStatus.ACTIVE && returned != null)) {
            throw BusinessException.invalid("日期不合法，归还日期不得早于借阅日期，也不能用更正直接完成归还");
        }
        Instant end = returned == null ? Instant.parse("2038-01-01T00:00:00Z") : returned;
        if (!sql.list(
                        """
                        SELECT id FROM loan WHERE copy_id=? AND id<>? AND status<>'CANCELLED'
                        AND borrowed_at<? AND (returned_at IS NULL OR returned_at>?) FOR UPDATE
                        """,
                        before.copyId(),
                        id,
                        end,
                        borrowed)
                .isEmpty()) {
            throw BusinessException.conflict("LOAN_TIME_OVERLAP", "更正后的日期与其他有效借阅重叠");
        }
        sql.update(
                "UPDATE loan SET borrowed_at=?,due_at=?,returned_at=?,note=?,version=version+1"
                        + " WHERE id=?",
                borrowed,
                due,
                returned,
                input.note() == null ? before.note() : input.note(),
                id);
        LoanView result = loans.find(id);
        audit.record(
                actor,
                returnOnly ? "CORRECT_RETURN" : "CORRECT_LOAN",
                "LOAN",
                id,
                before,
                result,
                input.reason());
        return result;
    }

    /** 锁定顺序始终是学生、副本、借阅；返回锁定后的最新状态。 */
    private LoanView lockLoan(CurrentUser actor, long id) {
        LoanView reference = find(actor, id);
        lockStudent(reference.studentId());
        lockCopy(reference.copyId());
        Map<String, Object> latest = sql.one("SELECT * FROM loan WHERE id=? FOR UPDATE", id);
        if (latest == null) {
            throw BusinessException.notFound();
        }
        // Joined descriptive data can come from the earlier read; all mutable loan fields use the
        // locking read.
        latest.put("student_no", reference.studentNo());
        latest.put("student_name", reference.studentName());
        latest.put("accession_no", reference.accessionNo());
        latest.put("edition_id", reference.editionId());
        latest.put("title", reference.title());
        return loans.view(latest);
    }

    private Map<String, Object> lockStudent(long id) {
        Map<String, Object> result = sql.one("SELECT * FROM student WHERE id=? FOR UPDATE", id);
        if (result == null) {
            throw BusinessException.notFound();
        }
        return result;
    }

    private Map<String, Object> lockCopy(long id) {
        Map<String, Object> result = sql.one("SELECT * FROM book_copy WHERE id=? FOR UPDATE", id);
        if (result == null) {
            throw BusinessException.notFound();
        }
        return result;
    }

    private long studentId(CurrentUser actor, Long requested) {
        if ("ADMIN".equals(actor.role())) {
            if (requested == null) {
                throw BusinessException.invalid("管理员代办时必须选择学生");
            }
            return requested;
        }
        if (actor.studentId() == null) {
            throw BusinessException.notFound();
        }
        return actor.studentId();
    }
}
