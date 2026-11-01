package library.tools;

import library.db.*;
import library.service.CirculationService;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.*;

/**
 * 数据库初始化工具，创建项目数据库、数据表、连接配置和初始数据。
 * 常量：DEMO 演示库名、TEST 测试库名、USER 应用账号。
 * 方法：读取管理员配置、创建数据库和表、写入连接配置及初始数据。
 */
public final class SetupMain {
    private static final String DEMO = "library_traditional",
            TEST = "library_traditional_test",
            USER = "library_trad_app";

    /**
     * 工具类使用静态方法，无需创建对象。
     */
    private SetupMain() {}

    /**
     * 初始化项目数据库、应用账号、连接配置和初始数据。
     *
     * @param args 两个参数：管理员配置文件路径、项目 config 目录路径
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("用法：SetupMain <管理员 properties> <工程 config 目录>");
        Path config = Path.of(args[1]);
        Files.createDirectories(config);
        if (Files.exists(config.resolve("db.properties"))) {
            try (DatabaseManager db = new DatabaseManager(config.resolve("db.properties"))) {
                System.out.println("专用数据库配置已存在，保留现有资料。 ");
            }
            return;
        }
        Properties admin = DatabaseManager.load(Path.of(args[0]));
        String server = serverUrl(admin.getProperty("url"));
        String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
        try (Connection c =
                DriverManager.getConnection(
                        server, admin.getProperty("user"), admin.getProperty("password"))) {
            for (String schema : List.of(DEMO, TEST)) {
                if (Jdbc.count(
                                c,
                                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                                schema)
                        > 0)
                    throw new IllegalStateException(
                            "数据库 " + schema + " 已存在但本地配置缺失；为保护数据，停止初始化，请核对后配置连接。");
            }
            if (Jdbc.count(
                            c,
                            "SELECT COUNT(*) FROM mysql.user WHERE User=? AND Host='localhost'",
                            USER)
                    > 0) throw new IllegalStateException("专用账号已存在，请先核对本地配置，工具不会重设已有账号密码。");
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "CREATE DATABASE "
                                + DEMO
                                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                s.executeUpdate(
                        "CREATE DATABASE "
                                + TEST
                                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                s.executeUpdate(
                        "CREATE USER '" + USER + "'@'localhost' IDENTIFIED BY '" + password + "'");
                s.executeUpdate(
                        "GRANT SELECT,INSERT,UPDATE,DELETE ON "
                                + DEMO
                                + ".* TO '"
                                + USER
                                + "'@'localhost'");
                s.executeUpdate(
                        "GRANT ALL PRIVILEGES ON " + TEST + ".* TO '" + USER + "'@'localhost'");
            }
        }
        for (String schema : List.of(DEMO, TEST)) {
            String url = databaseUrl(server, schema);
            try (Connection c =
                    DriverManager.getConnection(
                            url, admin.getProperty("user"), admin.getProperty("password"))) {
                createTables(c);
            }
            writeConfig(
                    config.resolve(schema.equals(DEMO) ? "db.properties" : "test-db.properties"),
                    url,
                    password);
        }
        try (DatabaseManager db = new DatabaseManager(config.resolve("db.properties"))) {
            seed(db);
        }
        System.out.println("初始化完成：20 位虚构学生、28 册真实书名样本、6 条演示借阅。账号仅获本工程数据库权限；密码仅保存在本机 config 中。");
    }

    /**
     * 从数据库连接地址中提取 MySQL 服务器地址。
     *
     * @param url MySQL 的 JDBC 连接地址
     * @return 不包含数据库名称的 JDBC 服务器地址
     */
    private static String serverUrl(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        return base.substring(0, base.lastIndexOf('/') + 1)
                + "?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai";
    }

    /**
     * 根据服务器地址和数据库名称生成连接地址。
     *
     * @param server MySQL 服务器的 JDBC 地址
     * @param schema 数据库名称
     * @return 指定数据库的 JDBC 连接地址
     */
    private static String databaseUrl(String server, String schema) {
        return server.replace("/?", "/" + schema + "?");
    }

    /**
     * 在当前数据库中创建项目所需的数据表。
     *
     * @param c 当前操作使用的数据库连接
     */
    public static void createTables(Connection c) throws Exception {
        String schema = c.getCatalog();
        if (!List.of(DEMO, TEST).contains(schema))
            throw new IllegalArgumentException("建表工具仅允许本工程的两个专用数据库。");
        try (InputStream in = SetupMain.class.getResourceAsStream("/schema.sql")) {
            String sql =
                    new String(
                            Objects.requireNonNull(in).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            try (Statement s = c.createStatement()) {
                for (String statement : sql.split(";")) {
                    if (!statement.isBlank()) s.execute(statement);
                }
            }
        }
    }

    /**
     * 将数据库连接信息写入配置文件。
     *
     * @param path 要创建的连接配置文件路径
     * @param url MySQL 的 JDBC 连接地址
     * @param password 数据库密码
     */
    private static void writeConfig(Path path, String url, String password) throws IOException {
        String data =
                "# 本机私有配置，不进入提交包\nurl=" + url + "\nuser=" + USER + "\npassword=" + password + "\n";
        Files.writeString(path, data, StandardOpenOption.CREATE_NEW);
    }

    /**
     * 写入初始学生、图书和借还记录。
     *
     * @param db 数据库连接管理器
     */
    private static void seed(DatabaseManager db) throws Exception {
        String[] names = {
            "林知远", "陈嘉宁", "许书言", "周予安", "沈念", "宋清禾", "方亦舟", "陆星野", "江以南", "顾明川", "苏雨桐", "唐可心", "季言",
            "程书瑶", "叶知秋", "白若溪", "许景行", "夏知夏", "温予晴", "孟子谦"
        };
        String[][] majors = {
            {"信息科学与工程学院", "自动化"}, {"计算机科学与工程学院", "计算机科学与技术"}, {"文法学院", "新闻学"}, {"理学院", "应用数学"}
        };
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < names.length; i++)
                    Jdbc.update(
                            c,
                            "INSERT INTO student(student_no,name,college,major) VALUES(?,?,?,?)",
                            "202425" + String.format("%02d", i + 1),
                            names[i],
                            majors[i % 4][0],
                            majors[i % 4][1]);
                try (InputStream in = SetupMain.class.getResourceAsStream("/books.tsv");
                        BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                Objects.requireNonNull(in),
                                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    int i = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("#") || line.isBlank()) continue;
                        String[] v = line.split("\t");
                        Jdbc.update(
                                c,
                                "INSERT INTO book(book_no,title,author,edition) VALUES(?,?,?,?)",
                                "BK-" + String.format("%04d", ++i),
                                v[0],
                                v[1],
                                v[2]);
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        CirculationService service = new CirculationService(db);
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        for (int i = 1; i <= 6; i++) {
            long id =
                    service.borrow(
                            "202425" + String.format("%02d", i),
                            "BK-" + String.format("%04d", i),
                            now.minusDays(8 - i),
                            "课程演示样本（学生与借阅情境均为虚构）");
            if (i <= 3) service.giveBack(id, now.minusDays(3 - i).minusHours(2), "演示归还登记");
        }
    }
}
