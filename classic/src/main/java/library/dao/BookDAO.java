package library.dao;

import library.db.*;
import library.model.*;
import library.service.BusinessException;
import library.service.CoverStorage;
import java.sql.*;
import java.util.*;

/**
 * 图书数据访问类，负责查询、保存和删除馆藏图书。
 * 属性：db 提供数据库连接。
 * 方法：分页查询、按编号或书号查询、保存、删除和锁定记录。
 */
public class BookDAO {
    private final DatabaseManager db;

    /**
     * 创建图书数据访问对象。
     *
     * @param db 数据库连接管理器
     */
    public BookDAO(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 分页查询图书。
     *
     * @param q 馆藏书号、书名或作者的查询关键词
     * @param state 图书状态；null 表示全部状态
     * @param page 要查询的页码，从 1 开始
     * @param size 每页记录数
     * @return 当前页图书数据和分页信息
     */
    public Page<BookPO> list(String q, BookStatus state, int page, int size) throws SQLException {
        String where = " WHERE (book_no LIKE ? OR title LIKE ? OR author LIKE ?)";
        List<Object> args = new ArrayList<>(List.of("%" + q + "%", "%" + q + "%", "%" + q + "%"));
        if (state != null) {
            where += " AND status=?";
            args.add(state.name());
        }
        try (Connection c = db.getConnection()) {
            long n = Jdbc.count(c, "SELECT COUNT(*) FROM book" + where, args.toArray());
            int pn = (int) Math.min(page, Math.max(1, (n + size - 1) / size));
            args.add(size);
            args.add((pn - 1) * size);
            List<BookPO> rows = new ArrayList<>();
            try (PreparedStatement s =
                    c.prepareStatement(
                            "SELECT * FROM book" + where + " ORDER BY book_no LIMIT ? OFFSET ?")) {
                Jdbc.bind(s, args.toArray());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) rows.add(map(r));
                }
            }
            return new Page<>(rows, n, pn, size);
        }
    }

    /**
     * 按内部编号查询图书。
     *
     * @param id 图书内部编号
     * @return 图书对象；查不到时返回 null
     */
    public BookPO find(long id) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT * FROM book WHERE id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    /**
     * 按馆藏书号查询图书。
     *
     * @param number 完整馆藏书号
     * @return 图书对象；查不到时返回 null
     */
    public BookPO findByNumber(String number) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT * FROM book WHERE book_no=?")) {
            s.setString(1, number);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? map(r) : null;
            }
        }
    }

    /**
     * 新增或修改图书资料。
     *
     * @param p 图书资料；id 为 0 表示新增，否则修改
     */
    public void save(BookPO p) throws SQLException {
        if (p.getCoverPath() != null && !CoverStorage.isValidPath(p.getCoverPath()))
            throw new BusinessException("封面路径格式不正确。");
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (p.getId() == 0)
                    Jdbc.update(
                            c,
                            "INSERT INTO book(book_no,title,author,edition,status,cover_path) VALUES(?,?,?,?,'AVAILABLE',?)",
                            p.getBookNo(),
                            p.getTitle(),
                            p.getAuthor(),
                            p.getEdition(),
                            p.getCoverPath());
                else {
                    BookStatus current = lock(c, p.getId());
                    BookStatus desired = p.getStatus();
                    if (current == BookStatus.BORROWED) {
                        if (desired != BookStatus.BORROWED)
                            throw new BusinessException("图书仍在借阅中，请先归还，再调整馆藏状态。");
                    } else if (desired != BookStatus.AVAILABLE && desired != BookStatus.OFF_SHELF)
                        throw new BusinessException("请通过借阅登记变更在借状态。");
                    Jdbc.update(
                            c,
                            "UPDATE book SET title=?,author=?,edition=?,status=?,cover_path=? WHERE id=?",
                            p.getTitle(),
                            p.getAuthor(),
                            p.getEdition(),
                            desired.name(),
                            p.getCoverPath(),
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
     * 删除图书；有借阅历史时改为下架。
     *
     * @param id 图书内部编号
     * @return 删除或下架的结果提示
     */
    public String delete(long id) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (lock(c, id) == BookStatus.BORROWED)
                    throw new BusinessException("这册书尚未归还，不能删除或下架。");
                boolean history =
                        Jdbc.count(c, "SELECT COUNT(*) FROM loan WHERE book_id=?", id) > 0;
                Jdbc.update(
                        c,
                        history
                                ? "UPDATE book SET status='OFF_SHELF' WHERE id=?"
                                : "DELETE FROM book WHERE id=?",
                        id);
                c.commit();
                return history ? "图书已下架，历史借阅资料已保留。" : "图书已删除。";
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * 锁定图书记录。
     *
     * @param c 当前操作使用的数据库连接
     * @param id 图书内部编号
     * @return 图书当前的状态
     */
    public static BookStatus lock(Connection c, long id) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("SELECT status FROM book WHERE id=? FOR UPDATE")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new BusinessException("图书不存在或已删除。");
                return BookStatus.valueOf(r.getString(1));
            }
        }
    }

    /**
     * 将一行查询结果转换为图书对象。
     *
     * @param r 已定位到当前行的查询结果
     * @return 图书对象
     */
    private BookPO map(ResultSet r) throws SQLException {
        BookPO p = new BookPO();
        p.setId(r.getLong("id"));
        p.setBookNo(r.getString("book_no"));
        p.setTitle(r.getString("title"));
        p.setAuthor(r.getString("author"));
        p.setEdition(r.getString("edition"));
        p.setStatus(BookStatus.valueOf(r.getString("status")));
        p.setCoverPath(r.getString("cover_path"));
        return p;
    }
}
