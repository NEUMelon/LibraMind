package library.dao;

import library.db.*;
import library.model.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * 借阅记录数据访问类，负责查询和新增借阅记录。
 * 属性：db 提供数据库连接，JOIN 和 SELECT 保存联表查询语句。
 * 方法：分页查询、按编号查询、新增记录和查询统计数据。
 */
public class LoanDAO {
    private final DatabaseManager db;
    private static final String JOIN =
            " FROM loan l JOIN student s ON s.id=l.student_id JOIN book b ON b.id=l.book_id ";
    private static final String SELECT =
            "SELECT l.*,s.student_no,s.name AS student_name,b.book_no,b.title AS book_title" + JOIN;

    /**
     * 创建借阅数据访问对象。
     *
     * @param db 数据库连接管理器
     */
    public LoanDAO(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 分页查询借阅或归还记录。
     *
     * @param q 学号、姓名、书号或书名的查询关键词
     * @param state 借阅状态；null 表示默认排除已作废记录
     * @param from 开始日期，包含当天；可为 null
     * @param to 结束日期，包含当天；可为 null
     * @param returns true 查询已归还记录，false 查询借阅记录
     * @param page 要查询的页码，从 1 开始
     * @param size 每页记录数
     * @return 当前页借阅记录和分页信息
     */
    public Page<LoanPO> list(
            String q,
            LoanStatus state,
            LocalDate from,
            LocalDate to,
            boolean returns,
            int page,
            int size)
            throws SQLException {
        return list(q, state, from, to, returns, page, size, "");
    }

    /**
     * 分页查询借阅或归还记录。
     *
     * @param q 学号、姓名、书号或书名的查询关键词
     * @param state 借阅状态；null 表示默认排除已作废记录
     * @param from 开始日期，包含当天；可为 null
     * @param to 结束日期，包含当天；可为 null
     * @param returns true 查询已归还记录，false 查询借阅记录
     * @param page 要查询的页码，从 1 开始
     * @param size 每页记录数
     * @param studentNo 学生学号；筛选时为空表示不限学号
     * @return 当前页借阅记录和分页信息
     */
    public Page<LoanPO> list(
            String q,
            LoanStatus state,
            LocalDate from,
            LocalDate to,
            boolean returns,
            int page,
            int size,
            String studentNo)
            throws SQLException {
        String where =
                " WHERE (s.student_no LIKE ? OR s.name LIKE ? OR b.book_no LIKE ? OR b.title LIKE ?)";
        List<Object> args =
                new ArrayList<>(
                        List.of("%" + q + "%", "%" + q + "%", "%" + q + "%", "%" + q + "%"));
        if (!studentNo.isBlank()) {
            where += " AND s.student_no=?";
            args.add(studentNo);
        }
        if (returns) {
            where += " AND l.status='RETURNED'";
        } else if (state != null) {
            where += " AND l.status=?";
            args.add(state.name());
        } else where += " AND l.status<>'CANCELLED'";
        String date = returns ? "l.returned_at" : "l.borrowed_at";
        if (from != null) {
            where += " AND " + date + ">=?";
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            where += " AND " + date + "<?";
            args.add(to.plusDays(1).atStartOfDay());
        }
        try (Connection c = db.getConnection()) {
            long n = Jdbc.count(c, "SELECT COUNT(*)" + JOIN + where, args.toArray());
            int pn = (int) Math.min(page, Math.max(1, (n + size - 1) / size));
            args.add(size);
            args.add((pn - 1) * size);
            List<LoanPO> rows = new ArrayList<>();
            try (PreparedStatement s =
                    c.prepareStatement(
                            SELECT
                                    + where
                                    + " ORDER BY "
                                    + date
                                    + " DESC,l.id DESC LIMIT ? OFFSET ?")) {
                Jdbc.bind(s, args.toArray());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) rows.add(map(r));
                }
            }
            return new Page<>(rows, n, pn, size);
        }
    }

    /**
     * 按编号查询借阅记录。
     *
     * @param id 借阅记录内部编号
     * @return 借阅记录对象；查不到时返回 null
     */
    public LoanPO find(long id) throws SQLException {
        try (Connection c = db.getConnection()) {
            return find(c, id, false);
        }
    }

    /**
     * 按编号查询借阅记录。
     *
     * @param c 当前操作使用的数据库连接
     * @param id 借阅记录内部编号
     * @param lock 是否锁定查到的借阅记录
     * @return 借阅记录对象；查不到时返回 null
     */
    public LoanPO find(Connection c, long id, boolean lock) throws SQLException {
        // 锁定时只锁 loan 行，避免联表 FOR UPDATE 取得不必要的锁。
        if (lock) {
            try (PreparedStatement s =
                    c.prepareStatement("SELECT id FROM loan WHERE id=? FOR UPDATE")) {
                s.setLong(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return null;
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement(SELECT + " WHERE l.id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    /**
     * 新增一条借阅记录。
     *
     * @param c 当前操作使用的数据库连接
     * @param sid 学生内部编号
     * @param bid 图书内部编号
     * @param at 借阅时间
     * @param note 备注
     * @return 新增记录的内部编号
     */
    public long insert(Connection c, long sid, long bid, java.time.LocalDateTime at, String note)
            throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement(
                        "INSERT INTO loan(student_id,book_id,borrowed_at,note) VALUES(?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            Jdbc.bind(s, sid, bid, at, note);
            s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /**
     * 查询首页的学生、图书和借还数量。
     *
     * @return 各统计项名称与数量的对应关系
     */
    public Map<String, Long> statistics() throws SQLException {
        try (Connection c = db.getConnection()) {
            return Map.of(
                    "students",
                    Jdbc.count(c, "SELECT COUNT(*) FROM student WHERE enabled=1"),
                    "available",
                    Jdbc.count(c, "SELECT COUNT(*) FROM book WHERE status='AVAILABLE'"),
                    "active",
                    Jdbc.count(c, "SELECT COUNT(*) FROM loan WHERE status='ACTIVE'"),
                    "returned",
                    Jdbc.count(c, "SELECT COUNT(*) FROM loan WHERE status='RETURNED'"),
                    "books",
                    Jdbc.count(c, "SELECT COUNT(*) FROM book WHERE status<>'OFF_SHELF'"));
        }
    }

    /**
     * 将一行查询结果转换为借阅记录对象。
     *
     * @param r 已定位到当前行的查询结果
     * @return 借阅记录对象
     */
    private LoanPO map(ResultSet r) throws SQLException {
        LoanPO p = new LoanPO();
        p.setId(r.getLong("id"));
        p.setStudentId(r.getLong("student_id"));
        p.setBookId(r.getLong("book_id"));
        p.setStudentNo(r.getString("student_no"));
        p.setStudentName(r.getString("student_name"));
        p.setBookNo(r.getString("book_no"));
        p.setBookTitle(r.getString("book_title"));
        p.setStatus(LoanStatus.valueOf(r.getString("status")));
        p.setNote(r.getString("note"));
        p.setBorrowedAt(r.getTimestamp("borrowed_at").toLocalDateTime());
        Timestamp t = r.getTimestamp("returned_at");
        p.setReturnedAt(t == null ? null : t.toLocalDateTime());
        return p;
    }
}
