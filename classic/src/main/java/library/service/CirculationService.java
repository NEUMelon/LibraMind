package library.service;

import library.dao.*;
import library.db.*;
import library.model.LoanPO;
import library.model.BookStatus;
import library.model.LoanStatus;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 借还业务类，负责借书、还书、更正日期和作废记录。
 * 属性：db 提供数据库连接，loans 操作借阅记录。
 * 方法：borrow 借书、giveBack 还书、cancel 作废、undoReturn 撤销归还、correct 更正日期。
 */
public class CirculationService {
    private final DatabaseManager db;
    private final LoanDAO loans;

    /**
     * 创建借还业务对象。
     *
     * @param db 数据库连接管理器
     */
    public CirculationService(DatabaseManager db) {
        this.db = db;
        this.loans = new LoanDAO(db);
    }

    /**
     * 登记借书，增加学生已借数量并将图书改为在借。
     *
     * @param studentNo 完整学生学号
     * @param bookNo 馆藏书号
     * @param at 实际借阅时间
     * @param note 备注
     * @return 新增借阅记录的内部编号
     */
    public long borrow(String studentNo, String bookNo, LocalDateTime at, String note)
            throws SQLException {
        validTime(at);
        validNote(note);
        return transaction(
                c -> {
                    long sid =
                            lookup(
                                    c,
                                    "SELECT id FROM student WHERE student_no=?",
                                    studentNo,
                                    "未找到该学号，请先登记学生资料。");
                    long bid =
                            lookup(
                                    c,
                                    "SELECT id FROM book WHERE book_no=?",
                                    bookNo,
                                    "未找到该书号，请先登记图书资料。");
                    if (!StudentDAO.lock(c, sid)) throw new BusinessException("该学生已停用，请先恢复学生资料。");
                    if (BookDAO.lock(c, bid) != BookStatus.AVAILABLE)
                        throw new BusinessException("这册书目前不可借，可能已被其他操作者借出或下架。");
                    try (PreparedStatement s =
                            c.prepareStatement(
                                    "SELECT MAX(returned_at) FROM loan WHERE book_id=? AND status='RETURNED'")) {
                        s.setLong(1, bid);
                        try (ResultSet r = s.executeQuery()) {
                            r.next();
                            Timestamp last = r.getTimestamp(1);
                            if (last != null && at.isBefore(last.toLocalDateTime()))
                                throw new BusinessException("借阅时间不能早于这册书上一次的归还时间。");
                        }
                    }
                    long id = loans.insert(c, sid, bid, at, note);
                    StudentDAO.changeBorrowedCount(c, sid, 1);
                    if (Jdbc.update(
                                    c,
                                    "UPDATE book SET status='BORROWED' WHERE id=? AND status='AVAILABLE'",
                                    bid)
                            != 1) throw new BusinessException("图书状态已变化，请刷新后重试。");
                    return id;
                });
    }

    /**
     * 登记还书，减少学生已借数量并将图书改为可借。
     *
     * @param id 借阅记录内部编号
     * @param at 实际归还时间
     * @param reason 归还备注，可为空字符串
     */
    public void giveBack(long id, LocalDateTime at, String reason) throws SQLException {
        validTime(at);
        validNote(reason);
        transaction(
                c -> {
                    LoanPO p = locked(c, id);
                    if (p.getStatus() == LoanStatus.RETURNED) return null;
                    if (p.getStatus() != LoanStatus.ACTIVE)
                        throw new BusinessException("该借阅已作废，不能办理归还。");
                    if (at.isBefore(p.getBorrowedAt()))
                        throw new BusinessException("归还时间不能早于借阅时间。");
                    requireBorrowed(c, p);
                    Jdbc.update(
                            c,
                            "UPDATE loan SET status='RETURNED',returned_at=?,note=? WHERE id=?",
                            at,
                            append(p, "登记归还", reason),
                            id);
                    StudentDAO.changeBorrowedCount(c, p.getStudentId(), -1);
                    Jdbc.update(c, "UPDATE book SET status='AVAILABLE' WHERE id=?", p.getBookId());
                    return null;
                });
    }

    /**
     * 作废借阅记录；若尚未归还，同时释放图书并减少已借数量。
     *
     * @param id 借阅记录内部编号
     * @param reason 操作原因
     */
    public void cancel(long id, String reason) throws SQLException {
        requiredReason(reason);
        transaction(
                c -> {
                    LoanPO p = locked(c, id);
                    if (p.getStatus() == LoanStatus.CANCELLED)
                        throw new BusinessException("这条记录已经作废，无需重复操作。");
                    if (p.getStatus() == LoanStatus.ACTIVE) {
                        requireBorrowed(c, p);
                        StudentDAO.changeBorrowedCount(c, p.getStudentId(), -1);
                        Jdbc.update(
                                c, "UPDATE book SET status='AVAILABLE' WHERE id=?", p.getBookId());
                    }
                    Jdbc.update(
                            c,
                            "UPDATE loan SET status='CANCELLED',note=? WHERE id=?",
                            append(p, "作废借阅", reason),
                            id);
                    return null;
                });
    }

    /**
     * 撤销归还，将记录和图书恢复为在借，并增加已借数量。
     *
     * @param id 借阅记录内部编号
     * @param reason 操作原因
     */
    public void undoReturn(long id, String reason) throws SQLException {
        requiredReason(reason);
        transaction(
                c -> {
                    LoanPO p = locked(c, id);
                    if (p.getStatus() != LoanStatus.RETURNED)
                        throw new BusinessException("仅已归还记录可以撤销归还。");
                    long latest =
                            Jdbc.count(
                                    c,
                                    "SELECT COALESCE(MAX(id),0) FROM loan WHERE book_id=? AND status<>'CANCELLED'",
                                    p.getBookId());
                    if (latest != id) throw new BusinessException("该册书已有后续有效借阅，不能撤销这次归还。");
                    if (BookDAO.lock(c, p.getBookId()) != BookStatus.AVAILABLE)
                        throw new BusinessException("图书目前已借出或下架，不能恢复为在借。");
                    String note = append(p, "撤销归还（原归还时间 " + p.getReturnedText() + "）", reason);
                    Jdbc.update(
                            c,
                            "UPDATE loan SET status='ACTIVE',returned_at=NULL,note=? WHERE id=?",
                            note,
                            id);
                    StudentDAO.changeBorrowedCount(c, p.getStudentId(), 1);
                    Jdbc.update(c, "UPDATE book SET status='BORROWED' WHERE id=?", p.getBookId());
                    return null;
                });
    }

    /**
     * 更正借阅日期或归还日期。
     *
     * @param id 借阅记录内部编号
     * @param at 要处理的日期和时间
     * @param returnDate true 更正归还日期，false 更正借阅日期
     * @param reason 操作原因
     */
    public void correct(long id, LocalDateTime at, boolean returnDate, String reason)
            throws SQLException {
        validTime(at);
        requiredReason(reason);
        transaction(
                c -> {
                    LoanPO p = locked(c, id);
                    if (p.getStatus() == LoanStatus.CANCELLED)
                        throw new BusinessException("已作废记录不能再更正日期。");
                    if (returnDate && p.getStatus() != LoanStatus.RETURNED)
                        throw new BusinessException("尚未归还，不能更正归还日期。");
                    LocalDateTime start = returnDate ? p.getBorrowedAt() : at;
                    LocalDateTime end = returnDate ? at : p.getReturnedAt();
                    if (end != null && end.isBefore(start))
                        throw new BusinessException("归还时间不能早于借阅时间。");
                    try (PreparedStatement s =
                            c.prepareStatement(
                                    "SELECT id,borrowed_at,returned_at FROM loan WHERE book_id=? AND id<>? AND status<>'CANCELLED'")) {
                        Jdbc.bind(s, p.getBookId(), id);
                        try (ResultSet r = s.executeQuery()) {
                            while (r.next()) {
                                if (r.getLong("id") < id) {
                                    Timestamp prev = r.getTimestamp("returned_at");
                                    if (prev == null || start.isBefore(prev.toLocalDateTime()))
                                        throw new BusinessException("更正后的借阅时间与前一次借阅重叠。");
                                } else if (end == null
                                        || end.isAfter(
                                                r.getTimestamp("borrowed_at").toLocalDateTime()))
                                    throw new BusinessException("更正后的归还时间与后一次借阅重叠。");
                            }
                        }
                    }
                    String field = returnDate ? "returned_at" : "borrowed_at";
                    String old = returnDate ? p.getReturnedText() : p.getBorrowedText();
                    Jdbc.update(
                            c,
                            "UPDATE loan SET " + field + "=?,note=? WHERE id=?",
                            at,
                            append(
                                    p,
                                    "更正" + (returnDate ? "归还" : "借阅") + "日期（原值 " + old + "）",
                                    reason),
                            id);
                    return null;
                });
    }

    /**
     * 锁定学生、图书和借阅记录，读取最新借阅资料。
     *
     * @param c 当前操作使用的数据库连接
     * @param id 借阅记录内部编号
     * @return 锁定后的借阅记录
     */
    private LoanPO locked(Connection c, long id) throws SQLException {
        LoanPO p = loans.find(c, id, false);
        if (p == null) throw new BusinessException("借阅记录不存在。");
        StudentDAO.lock(c, p.getStudentId());
        BookDAO.lock(c, p.getBookId());
        return loans.find(c, id, true);
    }

    /**
     * 检查借阅记录对应的图书是否处于在借状态。
     *
     * @param c 当前操作使用的数据库连接
     * @param p 当前借阅记录
     */
    private void requireBorrowed(Connection c, LoanPO p) throws SQLException {
        if (BookDAO.lock(c, p.getBookId()) != BookStatus.BORROWED)
            throw new BusinessException("馆藏状态与借阅不一致，请先核对记录。");
    }

    /**
     * 按学号或书号查询数据库内部编号。
     *
     * @param c 当前操作使用的数据库连接
     * @param sql 要执行的 SQL 语句
     * @param no 完整学号或馆藏书号
     * @param error 查不到记录时的错误提示
     * @return 查询到的内部编号
     */
    private long lookup(Connection c, String sql, String no, String error) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, no);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new BusinessException(error);
                return r.getLong(1);
            }
        }
    }

    /**
     * 检查借还时间是否在允许范围内。
     *
     * @param at 要处理的日期和时间
     */
    private void validTime(LocalDateTime at) {
        if (at == null || at.getYear() < 2000 || at.isAfter(LocalDateTime.now().plusMinutes(1)))
            throw new BusinessException("请选择 2000 年以后且不晚于当前时间的实际日期。");
    }

    /**
     * 检查备注是否为空值或超过 300 个字符。
     *
     * @param note 备注
     */
    private void validNote(String note) {
        if (note == null || note.length() > 300) throw new BusinessException("备注最多 300 个字符。");
    }

    /**
     * 检查操作原因是否已填写且不超过 300 个字符。
     *
     * @param reason 操作原因
     */
    private void requiredReason(String reason) {
        validNote(reason);
        if (reason.isBlank()) throw new BusinessException("请填写本次操作的原因。");
    }

    /**
     * 将操作名称和原因追加到借阅备注中。
     *
     * @param p 当前借阅记录
     * @param action 要记入备注的操作名称
     * @param reason 操作原因
     * @return 追加后的完整备注
     */
    private String append(LoanPO p, String action, String reason) {
        String line =
                "["
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        + "] "
                        + action
                        + (reason.isBlank() ? "" : "：" + reason);
        String note = p.getNote().isBlank() ? line : p.getNote() + "\n" + line;
        if (note.length() > 2000) throw new BusinessException("该记录更正说明已达长度上限，请先核对历史。");
        return note;
    }

    /**
     * 在一个事务中执行操作；成功提交，失败回滚。
     *
     * @param <T> 操作结果的类型
     * @param work 要在事务中执行的操作
     * @return 操作返回的结果
     */
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
    }

    /**
     * 事务操作接口，表示需要在一个事务中执行的操作。
     * 方法：run 接收数据库连接并返回操作结果。
     *
     * @param <T> 操作结果的类型
     */
    @FunctionalInterface
    private interface Work<T> {
        /**
         * 使用指定连接执行事务中的操作。
         *
         * @param c 当前操作使用的数据库连接
         * @return 操作返回的结果
         */
        T run(Connection c) throws SQLException;
    }
}
