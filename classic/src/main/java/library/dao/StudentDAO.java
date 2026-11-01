package library.dao;

import library.db.*;
import library.model.*;
import library.service.BusinessException;
import java.sql.*;
import java.util.*;

/**
 * 学生数据访问类，负责查询、保存和删除学生资料。
 * 属性：db 提供数据库连接，SELECT 保存查询语句前缀。
 * 方法：分页查询、按编号或学号查询、保存、删除、锁定记录、更新已借数量。
 */
public class StudentDAO {
    private final DatabaseManager db;
    private static final String SELECT = "SELECT s.* FROM student s ";

    /**
     * 创建学生数据访问对象。
     *
     * @param db 数据库连接管理器
     */
    public StudentDAO(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 分页查询学生。
     *
     * @param q 学号、姓名、学院或专业的查询关键词
     * @param state enabled 查询启用学生，disabled 查询停用学生，其他值不过滤
     * @param page 要查询的页码，从 1 开始
     * @param size 每页记录数
     * @return 当前页学生数据和分页信息
     */
    public Page<StudentPO> list(String q, String state, int page, int size) throws SQLException {
        String where =
                " WHERE (s.student_no LIKE ? OR s.name LIKE ? OR s.college LIKE ? OR s.major LIKE ?)";
        if ("enabled".equals(state)) where += " AND s.enabled=1";
        if ("disabled".equals(state)) where += " AND s.enabled=0";
        String like = "%" + q + "%";
        try (Connection c = db.getConnection()) {
            long n =
                    Jdbc.count(c, "SELECT COUNT(*) FROM student s" + where, like, like, like, like);
            int pn = (int) Math.min(page, Math.max(1, (n + size - 1) / size));
            List<StudentPO> rows = new ArrayList<>();
            try (PreparedStatement s =
                    c.prepareStatement(
                            SELECT + where + " ORDER BY s.student_no LIMIT ? OFFSET ?")) {
                Jdbc.bind(s, like, like, like, like, size, (pn - 1) * size);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) rows.add(map(r));
                }
            }
            return new Page<>(rows, n, pn, size);
        }
    }

    /**
     * 按内部编号查询学生。
     *
     * @param id 学生内部编号
     * @return 学生对象；查不到时返回 null
     */
    public StudentPO find(long id) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement(SELECT + " WHERE s.id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    /**
     * 按学号查询学生。
     *
     * @param number 完整学号
     * @return 学生对象；查不到时返回 null
     */
    public StudentPO findByNumber(String number) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement(SELECT + " WHERE s.student_no=?")) {
            s.setString(1, number);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    /**
     * 新增或修改学生资料。
     *
     * @param p 学生资料；id 为 0 表示新增，否则修改
     */
    public void save(StudentPO p) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (p.getId() == 0)
                    Jdbc.update(
                            c,
                            "INSERT INTO student(student_no,name,college,major,enabled) VALUES(?,?,?,?,1)",
                            p.getStudentNo(),
                            p.getName(),
                            p.getCollege(),
                            p.getMajor());
                else {
                    lock(c, p.getId());
                    Jdbc.update(
                            c,
                            "UPDATE student SET name=?,college=?,major=?,enabled=? WHERE id=?",
                            p.getName(),
                            p.getCollege(),
                            p.getMajor(),
                            p.isEnabled(),
                            p.getId());
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * 删除学生；有借阅历史时改为停用。
     *
     * @param id 学生内部编号
     * @return 删除或停用的结果提示
     */
    public String delete(long id) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                lock(c, id);
                if (Jdbc.count(
                                c,
                                "SELECT COUNT(*) FROM loan WHERE student_id=? AND status='ACTIVE'",
                                id)
                        > 0) throw new BusinessException("该学生仍有未归还图书，请先办理归还。");
                boolean history =
                        Jdbc.count(c, "SELECT COUNT(*) FROM loan WHERE student_id=?", id) > 0;
                Jdbc.update(
                        c,
                        history
                                ? "UPDATE student SET enabled=0 WHERE id=?"
                                : "DELETE FROM student WHERE id=?",
                        id);
                c.commit();
                return history ? "学生已停用，历史借阅资料已保留。" : "学生已删除。";
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * 锁定学生记录。
     *
     * @param c 当前操作使用的数据库连接
     * @param id 学生内部编号
     * @return true 表示已启用，false 表示已停用
     */
    public static boolean lock(Connection c, long id) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("SELECT enabled FROM student WHERE id=? FOR UPDATE")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new BusinessException("学生不存在或已删除。");
                return r.getBoolean(1);
            }
        }
    }

    /**
     * 增加或减少学生的已借数量。
     *
     * @param c 当前操作使用的数据库连接
     * @param id 学生内部编号
     * @param delta 数量变化，1 增加一册，-1 减少一册
     */
    public static void changeBorrowedCount(Connection c, long id, int delta) throws SQLException {
        if (delta != 1 && delta != -1) throw new IllegalArgumentException("数量只能按一次借还增减一册。");
        if (Jdbc.update(
                        c,
                        "UPDATE student SET borrowed_count=borrowed_count+? WHERE id=? AND borrowed_count+? >= 0",
                        delta,
                        id,
                        delta)
                != 1) throw new BusinessException("学生已借数量异常，请先核对借阅记录。");
    }

    /**
     * 将一行查询结果转换为学生对象。
     *
     * @param r 已定位到当前行的查询结果
     * @return 学生对象
     */
    private StudentPO map(ResultSet r) throws SQLException {
        StudentPO p = new StudentPO();
        p.setId(r.getLong("id"));
        p.setStudentNo(r.getString("student_no"));
        p.setName(r.getString("name"));
        p.setCollege(r.getString("college"));
        p.setMajor(r.getString("major"));
        p.setEnabled(r.getBoolean("enabled"));
        p.setBorrowedCount(r.getInt("borrowed_count"));
        return p;
    }
}
