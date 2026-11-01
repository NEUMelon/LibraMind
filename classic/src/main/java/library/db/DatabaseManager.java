package library.db;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/**
 * 数据库连接管理类，负责读取配置、创建和关闭连接池。
 * 属性：pool 保存数据库连接池。
 * 方法：load 读取配置、getConnection 获取连接、close 关闭连接池。
 */
public final class DatabaseManager implements AutoCloseable {
    private final DataSource pool;

    /**
     * 读取配置并创建数据库连接池。
     *
     * @param config 数据库连接配置文件的路径
     */
    public DatabaseManager(Path config) throws Exception {
        Properties p = load(config);
        PoolProperties opts = new PoolProperties();
        opts.setUrl(p.getProperty("url"));
        opts.setUsername(p.getProperty("user"));
        opts.setPassword(p.getProperty("password", ""));
        opts.setDriverClassName("com.mysql.cj.jdbc.Driver");
        opts.setInitialSize(1);
        opts.setMaxActive(12);
        opts.setMinIdle(1);
        opts.setMaxIdle(4);
        opts.setMaxWait(8000);
        opts.setTestOnBorrow(true);
        opts.setValidationQuery("SELECT 1");
        // Tomcat JDBC Pool 的归还回滚分支要求池默认值为 false；借出时再显式恢复 true。
        opts.setDefaultAutoCommit(false);
        opts.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        opts.setRollbackOnReturn(true);
        pool = new DataSource(opts);
        try (Connection c = getConnection()) {
            if (!"MySQL".equals(c.getMetaData().getDatabaseProductName()))
                throw new SQLException("本工程需要 MySQL 数据库");
        } catch (Exception e) {
            pool.close();
            throw e;
        }
    }

    /**
     * 读取数据库连接配置文件。
     *
     * @param path 数据库连接配置文件的路径
     * @return 配置中的键和值
     */
    public static Properties load(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) {
            p.load(r);
        }
        if (p.getProperty("url", "").isBlank() || p.getProperty("user", "").isBlank())
            throw new IOException("数据库配置缺少 url 或 user");
        return p;
    }

    /**
     * 从连接池获取一个数据库连接。
     *
     * @return 数据库连接，使用后需要关闭以归还连接池
     */
    public Connection getConnection() throws SQLException {
        Connection c = pool.getConnection();
        try {
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    /**
     * 关闭连接池，释放数据库连接。
     */
    @Override
    public void close() {
        pool.close();
    }
}
