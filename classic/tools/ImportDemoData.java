import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.io.*;

/** 课程演示数据导入工具：快照备份、校验唯一编号、事务追加；不覆盖已有资料。 */
public class ImportDemoData {
    /** inspect 只导出快照；apply 在完整校验后追加 data 目录中的两份 TSV。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("inspect", "apply").contains(args[0]))
            throw new IllegalArgumentException(
                    "ImportDemoData inspect|apply <config> <data-dir> <snapshot-dir>");
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(Path.of(args[1]))) {
            p.load(r);
        }
        try (Connection c =
                DriverManager.getConnection(
                        p.getProperty("url"), p.getProperty("user"), p.getProperty("password"))) {
            if (!"library_traditional".equals(c.getCatalog()))
                throw new IllegalArgumentException("仅允许 library_traditional");
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            Path snapshot = Path.of(args[3]);
            Files.createDirectories(snapshot);
            Map<String, List<String>> before = snapshot(c, snapshot);
            if (args[0].equals("inspect")) {
                c.rollback();
                System.out.println("Snapshot saved: " + snapshot);
                return;
            }
            List<String[]> students = read(Path.of(args[2], "students.tsv"), 4);
            List<String[]> books = read(Path.of(args[2], "books.tsv"), 4);
            validate(students, books);
            int[] added = {0, 0};
            try {
                Path covers = Path.of(args[2]).toAbsolutePath().getParent().resolve("covers");
                added[0] =
                        append(
                                c,
                                "student",
                                new String[] {"student_no", "name", "college", "major"},
                                students,
                                covers);
                added[1] =
                        append(
                                c,
                                "book",
                                new String[] {"book_no", "title", "author", "edition"},
                                books,
                                covers);
                for (var e : before.entrySet()) {
                    List<String> current = rows(c, e.getKey());
                    if (!new HashSet<>(current).containsAll(e.getValue()))
                        throw new SQLException("Original row changed: " + e.getKey());
                }
                c.commit();
                String report =
                        "students_added="
                                + added[0]
                                + "\nbooks_added="
                                + added[1]
                                + "\nstudents_input="
                                + students.size()
                                + "\nbooks_input="
                                + books.size()
                                + "\noriginal_rows_unchanged=true\nloans_added=0\n";
                Files.writeString(snapshot.resolve("import-result.txt"), report);
                System.out.println(report);
                try (var s = c.createStatement();
                        var r =
                                s.executeQuery(
                                        "SELECT (SELECT COUNT(*) FROM student),(SELECT COUNT(*) FROM book),(SELECT COUNT(*) FROM loan)")) {
                    r.next();
                    System.out.println(
                            "TOTAL students="
                                    + r.getLong(1)
                                    + " books="
                                    + r.getLong(2)
                                    + " loans="
                                    + r.getLong(3));
                }
                c.rollback();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 读取 TSV，要求固定列数；不接受空值、控制字符或重复编号。 */
    static List<String[]> read(Path path, int columns) throws Exception {
        List<String[]> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] v = line.split("\t", -1);
            if (v.length != columns
                    || Arrays.stream(v)
                            .anyMatch(x -> x.isBlank() || x.chars().anyMatch(ch -> ch < 32))
                    || !keys.add(v[0]))
                throw new IllegalArgumentException(
                        "Invalid or duplicate input row in " + path.getFileName());
            rows.add(v);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty input: " + path);
        return rows;
    }

    /** 校验学号年份、字段长度和书号格式，避免 MySQL 截断数据。 */
    static void validate(List<String[]> students, List<String[]> books) {
        for (String[] v : students)
            if (!v[0].matches("202[3-6][0-9]{4}")
                    || v[1].length() > 80
                    || v[2].length() > 120
                    || v[3].length() > 120)
                throw new IllegalArgumentException("Invalid student row");
        for (String[] v : books)
            if (!v[0].matches("CN-[0-9]{13}")
                    || v[1].length() > 200
                    || v[2].length() > 200
                    || v[3].length() > 80) throw new IllegalArgumentException("Invalid book row");
    }

    /** 相同编号且资料一致时跳过；同编号不同资料则整批回滚，不执行覆盖。 */
    static int append(
            Connection c, String table, String[] columns, List<String[]> values, Path covers)
            throws Exception {
        String names = String.join(",", columns);
        int added = 0;
        boolean book = table.equals("book");
        try (var find =
                        c.prepareStatement(
                                "SELECT "
                                        + names
                                        + " FROM "
                                        + table
                                        + " WHERE "
                                        + columns[0]
                                        + "=?");
                var insert =
                        c.prepareStatement(
                                "INSERT INTO "
                                        + table
                                        + "("
                                        + names
                                        + (book ? ",cover_path" : "")
                                        + ") VALUES(?,?,?,?"
                                        + (book ? ",?" : "")
                                        + ")")) {
            for (String[] v : values) {
                find.setString(1, v[0]);
                boolean exists;
                try (var r = find.executeQuery()) {
                    exists = r.next();
                    if (exists)
                        for (int i = 0; i < v.length; i++)
                            if (!Objects.equals(v[i], r.getString(i + 1)))
                                throw new SQLException("Conflicting key in " + table + ": " + v[0]);
                }
                if (!exists) {
                    for (int i = 0; i < v.length; i++) insert.setString(i + 1, v[i]);
                    if (book) insert.setString(5, coverFor(v[0], covers));
                    insert.executeUpdate();
                    added++;
                }
            }
        }
        return added;
    }

    /** 只在新增图书时建立本地封面关联；以后读图依据数据库路径，不依赖馆藏号。 */
    static String coverFor(String bookNo, Path directory) throws IOException {
        if (!bookNo.matches("CN-97[89][0-9]{10}")) return null;
        String name = bookNo.substring(3) + ".jpg";
        Path file = directory.resolve(name);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0
                ? name
                : null;
    }

    /** 导出完整三张表，保留原字段和主键；快照只写本机指定目录。 */
    static Map<String, List<String>> snapshot(Connection c, Path dir) throws Exception {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : List.of("student", "book", "loan")) {
            List<String> data = rows(c, table);
            all.put(table, data);
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY id");
                    var w =
                            Files.newBufferedWriter(
                                    dir.resolve(table + ".tsv"),
                                    StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE_NEW)) {
                var m = r.getMetaData();
                List<String> header = new ArrayList<>();
                for (int i = 1; i <= m.getColumnCount(); i++) header.add(m.getColumnName(i));
                w.write(String.join("\t", header));
                w.newLine();
                for (String row : data) {
                    w.write(row);
                    w.newLine();
                }
            }
        }
        return all;
    }

    /** 用反斜线转义导出行，确保换行备注和 NULL 能被区分及恢复。 */
    static List<String> rows(Connection c, String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            int n = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    String v = r.getString(i);
                    row.add(
                            v == null
                                    ? "\\N"
                                    : v.replace("\\", "\\\\")
                                            .replace("\t", "\\t")
                                            .replace("\r", "\\r")
                                            .replace("\n", "\\n"));
                }
                out.add(String.join("\t", row));
            }
        }
        return out;
    }
}
