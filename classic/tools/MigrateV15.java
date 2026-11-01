import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.sql.*;
import java.util.*;

/** v1.5 增量迁移：备份原表，新增数量和封面字段，回填后校验旧资料完整性。 */
public final class MigrateV15 {
    /** 参数：check|apply、目标连接配置、管理员配置、封面目录、全新备份目录。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !Set.of("check", "apply").contains(args[0]))
            throw new IllegalArgumentException(
                    "MigrateV15 check|apply <target-config> <admin-config> <covers-dir> <new-backup-dir>");
        Properties target = load(Path.of(args[1])), admin = load(Path.of(args[2]));
        if (!server(target.getProperty("url")).equals(server(admin.getProperty("url"))))
            throw new IllegalArgumentException("管理员配置必须连接同一台 MySQL。");
        try (Connection c =
                DriverManager.getConnection(
                        target.getProperty("url"),
                        admin.getProperty("user"),
                        admin.getProperty("password"))) {
            if (!Set.of("library_traditional", "library_traditional_test").contains(c.getCatalog()))
                throw new IllegalArgumentException("仅允许本项目演示库或测试库。");
            if (args[0].equals("check")) {
                System.out.println(report(c));
                return;
            }
            Path backup = Path.of(args[4]);
            Files.createDirectories(backup);
            c.setAutoCommit(false);
            Map<String, List<String>> before = snapshot(c, backup);
            c.commit();
            // MySQL DDL 会隐式提交。先备份，再做可重复的加列；数据回填另用事务。
            try (Statement s = c.createStatement()) {
                if (!hasColumn(c, "student", "borrowed_count"))
                    s.executeUpdate(
                            "ALTER TABLE student ADD COLUMN borrowed_count INT NOT NULL DEFAULT 0");
                if (!hasColumn(c, "book", "cover_path"))
                    s.executeUpdate("ALTER TABLE book ADD COLUMN cover_path VARCHAR(255) NULL");
                if (!hasConstraint(c, "chk_student_borrowed_count"))
                    s.executeUpdate(
                            "ALTER TABLE student ADD CONSTRAINT chk_student_borrowed_count CHECK (borrowed_count >= 0)");
            }
            try {
                c.setAutoCommit(false);
                // 与业务采用相同的锁顺序；迁移时应暂停旧版应用，避免它继续产生不维护计数的写入。
                try (Statement s = c.createStatement();
                        ResultSet r =
                                s.executeQuery("SELECT id FROM student ORDER BY id FOR UPDATE")) {
                    while (r.next()) {}
                }
                try (Statement s = c.createStatement();
                        ResultSet r =
                                s.executeQuery("SELECT id FROM book ORDER BY id FOR UPDATE")) {
                    while (r.next()) {}
                }
                try (Statement s = c.createStatement()) {
                    s.executeUpdate(
                            "UPDATE student s LEFT JOIN (SELECT student_id,COUNT(*) n FROM loan WHERE status='ACTIVE' GROUP BY student_id) x ON x.student_id=s.id SET s.borrowed_count=COALESCE(x.n,0)");
                }
                int linked = 0;
                Path covers = Path.of(args[3]);
                if (Files.isDirectory(covers))
                    try (var files = Files.list(covers);
                            PreparedStatement s =
                                    c.prepareStatement(
                                            "UPDATE book SET cover_path=? WHERE book_no=? AND cover_path IS NULL")) {
                        for (Path file :
                                files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                                        .sorted()
                                        .toList()) {
                            String name = file.getFileName().toString();
                            if (!name.matches("97[89][0-9]{10}\\.jpg") || Files.size(file) == 0)
                                continue;
                            s.setString(1, name);
                            s.setString(2, "CN-" + name.substring(0, 13));
                            linked += s.executeUpdate();
                        }
                    }
                for (var e : before.entrySet())
                    if (!e.getValue().equals(stableRows(c, e.getKey())))
                        throw new SQLException("原字段或记录被意外修改：" + e.getKey());
                if (mismatches(c) != 0) throw new SQLException("回填后数量与在借记录不一致。");
                c.commit();
                String result = report(c);
                c.rollback();
                Files.writeString(
                        backup.resolve("migration-result.json"),
                        result + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                System.out.println(result);
                System.out.println(
                        "New cover associations: "
                                + linked
                                + "; original rows unchanged; backup: "
                                + backup);
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 只读 UTF-8 配置，不输出凭据。 */
    static Properties load(Path path) throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) {
            p.load(r);
        }
        for (String key : List.of("url", "user", "password"))
            if (p.getProperty(key) == null) throw new IllegalArgumentException("配置缺少 " + key);
        return p;
    }

    /** 比较 MySQL 主机与端口，localhost 与 127.0.0.1 视为同一实例。 */
    static String server(String jdbc) throws Exception {
        URI uri = new URI(jdbc.substring(5));
        String host = uri.getHost();
        if (Set.of("localhost", "127.0.0.1").contains(host)) host = "127.0.0.1";
        return host + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort());
    }

    /** 检查是否已经加列，重复运行不重复 ALTER。 */
    static boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (PreparedStatement s =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?")) {
            s.setString(1, c.getCatalog());
            s.setString(2, table);
            s.setString(3, column);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1) > 0;
            }
        }
    }

    /** 检查非负数量约束。 */
    static boolean hasConstraint(Connection c, String name) throws Exception {
        try (PreparedStatement s =
                c.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=? AND table_name='student' AND constraint_name=?")) {
            s.setString(1, c.getCatalog());
            s.setString(2, name);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1) > 0;
            }
        }
    }

    /** 快照包含原表所有字段和 CREATE TABLE；新目录用于避免覆盖任何旧备份。 */
    static Map<String, List<String>> snapshot(Connection c, Path dir) throws Exception {
        Map<String, List<String>> stable = new LinkedHashMap<>();
        StringBuilder schema = new StringBuilder();
        for (String table : List.of("student", "book", "loan")) {
            stable.put(table, stableRows(c, table));
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SHOW CREATE TABLE " + table)) {
                r.next();
                schema.append(r.getString(2)).append(";\n\n");
            }
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM " + table + " ORDER BY id");
                    BufferedWriter w =
                            Files.newBufferedWriter(
                                    dir.resolve(table + ".tsv"),
                                    StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE_NEW)) {
                int n = r.getMetaData().getColumnCount();
                List<String> header = new ArrayList<>();
                for (int i = 1; i <= n; i++) header.add(r.getMetaData().getColumnName(i));
                w.write(String.join("\t", header));
                w.newLine();
                while (r.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= n; i++) cells.add(escape(r.getString(i)));
                    w.write(String.join("\t", cells));
                    w.newLine();
                }
            }
        }
        Files.writeString(
                dir.resolve("schema-before.sql"),
                schema,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        return stable;
    }

    /** 对比所有旧字段，忽略本次有意新增或回填的两个字段。 */
    static List<String> stableRows(Connection c, String table) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            var m = r.getMetaData();
            while (r.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= m.getColumnCount(); i++)
                    if (!Set.of("borrowed_count", "cover_path").contains(m.getColumnName(i)))
                        cells.add(escape(r.getString(i)));
                rows.add(String.join("\t", cells));
            }
        }
        return rows;
    }

    /** NULL、换行等内容在快照中保留并转义。 */
    static String escape(String v) {
        return v == null
                ? "\\N"
                : v.replace("\\", "\\\\")
                        .replace("\t", "\\t")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n");
    }

    /** 计算持久化数量与有效在借记录不一致的学生数。 */
    static long mismatches(Connection c) throws Exception {
        return count(
                c,
                "SELECT COUNT(*) FROM student s WHERE s.borrowed_count<>(SELECT COUNT(*) FROM loan l WHERE l.student_id=s.id AND l.status='ACTIVE')");
    }

    /** 返回标量，不输出学生姓名或连接配置。 */
    static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** 输出迁移后的校验摘要；check 模式可只读查看旧库状态。 */
    static String report(Connection c) throws Exception {
        boolean field = hasColumn(c, "student", "borrowed_count"),
                cover = hasColumn(c, "book", "cover_path");
        return "{\"database\":\""
                + c.getCatalog()
                + "\",\"borrowed_count_column\":"
                + field
                + ",\"cover_path_column\":"
                + cover
                + ",\"students\":"
                + count(c, "SELECT COUNT(*) FROM student")
                + ",\"books\":"
                + count(c, "SELECT COUNT(*) FROM book")
                + ",\"loans\":"
                + count(c, "SELECT COUNT(*) FROM loan")
                + ",\"active_loans\":"
                + count(c, "SELECT COUNT(*) FROM loan WHERE status='ACTIVE'")
                + ",\"stored_count_sum\":"
                + (field ? count(c, "SELECT COALESCE(SUM(borrowed_count),0) FROM student") : -1)
                + ",\"count_mismatches\":"
                + (field ? mismatches(c) : -1)
                + ",\"cover_links\":"
                + (cover ? count(c, "SELECT COUNT(*) FROM book WHERE cover_path IS NOT NULL") : 0)
                + "}";
    }
}
