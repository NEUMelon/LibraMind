import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Properties;

/** 一次性建立现代版独立开发/测试库，凭据仅写入被忽略的私有配置。 */
public class PrepareLocalDatabase {

    private static final String USER = "libramind_dev";

    /** 输入已有管理员配置与项目目录；如果目标已存在，停止而不是覆盖。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected admin-properties and project-root");
        }
        Path project = Path.of(args[1]).toAbsolutePath().normalize();
        Path local = project.resolve("config/application-local.properties");
        if (Files.exists(local)) {
            throw new IllegalStateException("Local configuration exists; no changes made");
        }
        Properties admin = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            admin.load(reader);
        }
        String url = admin.getProperty("url");
        String username = admin.getProperty("user", admin.getProperty("username"));
        String password = admin.getProperty("password");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Admin configuration is incomplete");
        }
        String dbPassword = randomPassword();
        String adminPassword = randomPassword();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("""
                            SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
                            WHERE SCHEMA_NAME IN ('libramind', 'libramind_test')
                            """)) {
                if (rows.next()) {
                    throw new IllegalStateException("Target database exists; no changes made");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM mysql.user WHERE User = ? AND Host = '127.0.0.1'")) {
                statement.setString(1, USER);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        throw new IllegalStateException("Target account exists; no changes made");
                    }
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE libramind CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                statement.execute("CREATE DATABASE libramind_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                statement.execute("CREATE USER '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + dbPassword + "'");
                for (String database : new String[] {"libramind", "libramind_test"}) {
                    statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON "
                            + database + ".* TO '" + USER + "'@'127.0.0.1'");
                }
            }
        }
        Properties settings = new Properties();
        settings.setProperty("spring.datasource.url", dbUrl("libramind"));
        settings.setProperty("spring.datasource.username", USER);
        settings.setProperty("spring.datasource.password", dbPassword);
        settings.setProperty("app.bootstrap.username", "admin");
        settings.setProperty("app.bootstrap.password", adminPassword);
        settings.setProperty("app.ai.key-file", "");
        Files.createDirectories(local.getParent());
        try (var output = Files.newOutputStream(local)) {
            settings.store(output, "Private local settings. Never commit, log or package this file.");
        }
        settings.setProperty("spring.datasource.url", dbUrl("libramind_test"));
        try (var output = Files.newOutputStream(project.resolve("config/test-local.properties"))) {
            settings.store(output, "Private integration test settings. Dedicated test schema only.");
        }
        System.out.println("Created libramind and libramind_test; private configuration saved. No credentials printed.");
    }

    /** 返回单个数据库的本机 JDBC 地址，数据库会话统一使用 UTC。 */
    private static String dbUrl(String database) {
        return "jdbc:mysql://127.0.0.1:3306/" + database
                + "?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&connectTimeout=5000&socketTimeout=10000";
    }

    /** 返回不含 SQL 引号或空白的随机密码，不输出到控制台。 */
    private static String randomPassword() {
        byte[] bytes = new byte[30];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
