package com.libramind.ai;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.CopyStatus;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;
import com.libramind.loan.LoanDtos.*;
import com.libramind.loan.LoanService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 保存待确认借还操作；准备只生成快照，确认通过普通业务服务执行一次事务。 */
@Service
public class PendingActionService {

    public record Snapshot(
            Long studentId,
            long copyId,
            String accessionNo,
            Long loanId,
            long loanVersion,
            String title,
            Instant expectedDueAt) {}

    public record ActionView(
            String id,
            String type,
            String status,
            Instant expiresAt,
            Snapshot preview,
            @io.swagger.v3.oas.annotations.media.Schema(
                            oneOf = {BorrowResult.class, LoanView.class})
                    Object result,
            String failureCode) {}

    private final com.libramind.loan.CirculationRules rules;
    private final SqlStore sql;
    private final CatalogDAO catalog;
    private final LoanService loans;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public PendingActionService(
            SqlStore sql,
            CatalogDAO catalog,
            LoanService loans,
            ObjectMapper json,
            PlatformTransactionManager manager,
            com.libramind.loan.CirculationRules rules) {
        this.rules = rules;
        this.sql = sql;
        this.catalog = catalog;
        this.loans = loans;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 按明确的馆藏登录号准备借阅，返回五分钟有效的确认卡；不改变库存。 */
    @org.springframework.transaction.annotation.Transactional
    public ActionView prepareBorrow(
            CurrentUser actor, Long conversationId, Long studentId, String accessionNo) {
        var eligibility = loans.eligibility(actor, studentId);
        var copy = catalog.findAccession(accessionNo);
        if (copy == null) {
            throw BusinessException.notFound();
        }
        if (!eligibility.canBorrow()
                || copy.archived()
                || copy.circulationStatus() != CopyStatus.AVAILABLE) {
            throw BusinessException.conflict("BORROW_NOT_ELIGIBLE", "学生或副本当前不允许借阅");
        }
        return create(
                actor,
                conversationId,
                "BORROW",
                copy.id(),
                new Snapshot(
                        eligibility.studentId(),
                        copy.id(),
                        copy.accessionNo(),
                        null,
                        0,
                        copy.title(),
                        null));
    }

    /** 按本人可访问的借阅编号准备续借或归还；快照保留原记录版本。 */
    @org.springframework.transaction.annotation.Transactional
    public ActionView prepareLoan(
            CurrentUser actor, Long conversationId, long loanId, String type) {
        if (!List.of("RENEW", "RETURN").contains(type)) {
            throw BusinessException.invalid("操作类型不正确");
        }
        var loan = loans.find(actor, loanId);
        if (loan.status() != LoanStatus.ACTIVE) {
            throw BusinessException.conflict("LOAN_NOT_ACTIVE", "只能操作在借记录");
        }
        if (type.equals("RENEW") && (loan.overdue() || loan.renewCount() >= rules.maxRenewals())) {
            throw BusinessException.conflict("RENEW_NOT_ELIGIBLE", "该记录已逾期或达到续借上限");
        }
        var row = sql.one("SELECT version FROM loan WHERE id=?", loanId);
        return create(
                actor,
                conversationId,
                type,
                loanId,
                new Snapshot(
                        loan.studentId(),
                        loan.copyId(),
                        loan.accessionNo(),
                        loanId,
                        number(row, "version"),
                        loan.title(),
                        loan.dueAt()));
    }

    private ActionView create(
            CurrentUser actor, Long conversationId, String type, long target, Snapshot snapshot) {
        if (conversationId != null
                && sql.one(
                                "SELECT id FROM ai_conversation WHERE id=? AND user_id=? AND"
                                        + " status='ACTIVE' FOR UPDATE",
                                conversationId,
                                actor.id())
                        == null) {
            throw BusinessException.notFound();
        }
        String id = UUID.randomUUID().toString();
        sql.update(
                """
INSERT INTO pending_action(id,user_id,conversation_id,action_type,target_id,payload_snapshot,expires_at)
VALUES(?,?,?,?,?,?,?)
""",
                id,
                actor.id(),
                conversationId,
                type,
                target,
                json.writeValueAsString(snapshot),
                Instant.now().plus(5, ChronoUnit.MINUTES));
        return get(actor, id);
    }

    /** 查询当前用户的确认卡；过期卡立即标为 EXPIRED，不再执行。 */
    public ActionView get(CurrentUser actor, String id) {
        sql.update(
                "UPDATE pending_action SET status='EXPIRED' WHERE id=? AND user_id=? AND"
                        + " status='PENDING' AND expires_at<=UTC_TIMESTAMP(6)",
                id,
                actor.id());
        return view(owned(actor, id, false));
    }

    public List<ActionView> list(CurrentUser actor, long conversationId) {
        if (sql.count(
                        "SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=? AND"
                                + " status='ACTIVE'",
                        conversationId,
                        actor.id())
                == 0) {
            throw BusinessException.notFound();
        }
        sql.update(
                "UPDATE pending_action SET status='EXPIRED' WHERE user_id=? AND conversation_id=?"
                        + " AND status='PENDING' AND expires_at<=UTC_TIMESTAMP(6)",
                actor.id(),
                conversationId);
        return sql
                .list(
                        "SELECT * FROM pending_action WHERE user_id=? AND conversation_id=? ORDER"
                                + " BY created_at DESC LIMIT 20",
                        actor.id(),
                        conversationId)
                .stream()
                .map(this::view)
                .toList();
    }

    /** 确认卡状态与借还写入在同一事务提交，重复确认返回同一结果。 */
    public ActionView confirm(CurrentUser actor, String id) {
        try {
            return transaction.execute(
                    status -> {
                        var row = owned(actor, id, true);
                        if (!"PENDING".equals(text(row, "status"))) {
                            return view(row);
                        }
                        if (!instant(row, "expires_at").isAfter(Instant.now())) {
                            sql.update("UPDATE pending_action SET status='EXPIRED' WHERE id=?", id);
                            return view(owned(actor, id, false));
                        }
                        Snapshot snapshot =
                                json.readValue(text(row, "payload_snapshot"), Snapshot.class);
                        sql.one(
                                "SELECT id FROM student WHERE id=? FOR UPDATE",
                                snapshot.studentId());
                        var copy =
                                sql.one(
                                        "SELECT * FROM book_copy WHERE id=? FOR UPDATE",
                                        snapshot.copyId());
                        if (copy == null
                                || !snapshot.accessionNo().equals(text(copy, "accession_no"))) {
                            throw BusinessException.conflict("ACTION_STALE", "副本信息已改变，请重新准备操作");
                        }
                        if (snapshot.loanId() != null) {
                            var loan =
                                    sql.one(
                                            "SELECT * FROM loan WHERE id=? FOR UPDATE",
                                            snapshot.loanId());
                            if (loan == null || number(loan, "version") != snapshot.loanVersion()) {
                                throw BusinessException.conflict("ACTION_STALE", "借阅记录已改变，请重新准备操作");
                            }
                        }
                        if (!instant(row, "expires_at").isAfter(Instant.now())) {
                            sql.update("UPDATE pending_action SET status='EXPIRED' WHERE id=?", id);
                            return view(owned(actor, id, false));
                        }
                        Object result =
                                switch (text(row, "action_type")) {
                                    case "BORROW" ->
                                            loans.borrow(
                                                    actor,
                                                    new BorrowInput(
                                                            snapshot.studentId(),
                                                            List.of(snapshot.accessionNo()),
                                                            "AI确认借阅"),
                                                    null);
                                    case "RENEW" -> loans.renew(actor, snapshot.loanId(), null);
                                    case "RETURN" ->
                                            loans.giveBack(
                                                    actor,
                                                    new ReturnInput(snapshot.loanId(), null, null),
                                                    null);
                                    default -> throw BusinessException.invalid("操作类型不正确");
                                };
                        sql.update(
                                "UPDATE pending_action SET status='SUCCEEDED',result_json=? WHERE"
                                        + " id=?",
                                json.writeValueAsString(result),
                                id);
                        return view(owned(actor, id, false));
                    });
        } catch (BusinessException exception) {
            // 业务事务已回滚；失败状态单独保存，绝不把部分借还结果标为成功。
            transaction.executeWithoutResult(
                    status ->
                            sql.update(
                                    """
UPDATE pending_action SET status='FAILED',failure_code=? WHERE id=? AND user_id=? AND status='PENDING'
""",
                                    exception.code(),
                                    id,
                                    actor.id()));
            return get(actor, id);
        }
    }

    /** 取消尚未执行的本人操作；已成功的卡不会反向撤销业务。 */
    public ActionView cancel(CurrentUser actor, String id) {
        owned(actor, id, false);
        sql.update(
                "UPDATE pending_action SET status='CANCELLED' WHERE id=? AND user_id=? AND"
                        + " status='PENDING'",
                id,
                actor.id());
        return get(actor, id);
    }

    private Map<String, Object> owned(CurrentUser actor, String id, boolean lock) {
        var row =
                sql.one(
                        "SELECT * FROM pending_action WHERE id=? AND user_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        id,
                        actor.id());
        if (row == null) {
            throw BusinessException.notFound();
        }
        return row;
    }

    private ActionView view(Map<String, Object> row) {
        return new ActionView(
                text(row, "id"),
                text(row, "action_type"),
                text(row, "status"),
                instant(row, "expires_at"),
                json.readValue(text(row, "payload_snapshot"), Snapshot.class),
                row.get("result_json") == null
                        ? null
                        : json.readValue(text(row, "result_json"), Object.class),
                text(row, "failure_code"));
    }
}
