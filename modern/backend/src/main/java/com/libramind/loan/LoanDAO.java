package com.libramind.loan;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.common.api.PageResult;
import com.libramind.common.db.SqlStore;
import com.libramind.loan.LoanDtos.*;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 借阅查询及结果映射，借书页和还书页读取同一张 loan 表。 */
@Repository
public class LoanDAO {

    private static final String FROM =
            """
            FROM loan l JOIN student s ON s.id=l.student_id JOIN book_copy c ON c.id=l.copy_id
            JOIN book_edition e ON e.id=c.edition_id
            """;
    private static final String SELECT =
            "SELECT l.*,s.student_no,s.name AS student_name,c.accession_no,c.edition_id,e.title "
                    + FROM;
    private final SqlStore sql;

    public LoanDAO(SqlStore sql) {
        this.sql = sql;
    }

    public LoanView find(long id) {
        Map<String, Object> row = sql.one(SELECT + " WHERE l.id=?", id);
        return row == null ? null : view(row);
    }

    public PageResult<LoanView> list(
            Long studentId,
            String q,
            LoanStatus status,
            boolean returns,
            Instant from,
            Instant to,
            int page,
            int size) {
        PageResult.validate(page, size);
        String where = " WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (studentId != null) {
            where += " AND l.student_id=?";
            args.add(studentId);
        }
        if (returns) {
            where += " AND l.status='RETURNED'";
        } else if (status != null) {
            where += " AND l.status=?";
            args.add(status.name());
        } else {
            where += " AND l.status<>'CANCELLED'";
        }
        if (!q.isBlank()) {
            where +=
                    " AND (s.student_no LIKE ? OR s.name LIKE ? OR c.accession_no LIKE ? OR e.title"
                            + " LIKE ?)";
            args.addAll(List.of("%" + q + "%", "%" + q + "%", "%" + q + "%", "%" + q + "%"));
        }
        String date = returns ? "l.returned_at" : "l.borrowed_at";
        if (from != null) {
            where += " AND " + date + ">=?";
            args.add(from);
        }
        if (to != null) {
            where += " AND " + date + "<=?";
            args.add(to);
        }
        long total = sql.count("SELECT COUNT(*) " + FROM + where, args.toArray());
        args.add(size);
        args.add((page - 1L) * size);
        String order =
                status == LoanStatus.ACTIVE
                        ? " ORDER BY l.due_at,l.id"
                        : " ORDER BY l.borrowed_at DESC,l.id DESC";
        return PageResult.of(
                sql.list(SELECT + where + order + " LIMIT ? OFFSET ?", args.toArray()).stream()
                        .map(this::view)
                        .toList(),
                page,
                size,
                total);
    }

    /** 锁定读取在借记录，防止事务早期快照导致并发额度统计过期。 */
    public int activeCountForUpdate(long studentId) {
        return sql.list(
                        "SELECT id FROM loan WHERE student_id=? AND status='ACTIVE' FOR UPDATE",
                        studentId)
                .size();
    }

    public LoanView view(Map<String, Object> row) {
        Instant due = instant(row, "due_at");
        LoanStatus status = LoanStatus.valueOf(text(row, "status"));
        return new LoanView(
                number(row, "id"),
                number(row, "student_id"),
                text(row, "student_no"),
                text(row, "student_name"),
                number(row, "copy_id"),
                text(row, "accession_no"),
                number(row, "edition_id"),
                text(row, "title"),
                instant(row, "borrowed_at"),
                due,
                instant(row, "returned_at"),
                status,
                (int) number(row, "renew_count"),
                status == LoanStatus.ACTIVE && due.isBefore(Instant.now()),
                text(row, "note"),
                text(row, "cancellation_reason"));
    }
}
