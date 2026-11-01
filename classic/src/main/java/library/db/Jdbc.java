package library.db;

import java.sql.*;

/**
 * JDBC 工具类，封装常用的 SQL 操作。
 * 没有实例属性；方法均为静态方法：bind 绑定参数、count 查询统计数字、update 执行增删改。
 */
public final class Jdbc {
    /**
     * 工具类使用静态方法，无需创建对象。
     */
    private Jdbc() {}

    /**
     * 给 SQL 中的问号占位符依次绑定参数。
     *
     * @param s 需要绑定参数的 SQL 语句对象
     * @param args 按问号顺序传入的 SQL 参数值
     */
    public static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
    }

    /**
     * 执行统计查询，读取第一行第一列的数字。
     *
     * @param c 当前操作使用的数据库连接
     * @param sql 要执行的 SQL 语句
     * @param args 按问号顺序传入的 SQL 参数值
     * @return 查询得到的统计数字
     */
    public static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /**
     * 执行一条新增、修改或删除 SQL。
     *
     * @param c 当前操作使用的数据库连接
     * @param sql 要执行的 SQL 语句
     * @param args 按问号顺序传入的 SQL 参数值
     * @return SQL 影响的行数
     */
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        }
    }
}
