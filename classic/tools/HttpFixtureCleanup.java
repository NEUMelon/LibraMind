import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** HTTP 验收专用清理工具：只移除本次随机生成的 QA 样本，不处理正常馆藏。 */
public class HttpFixtureCleanup {
    /** 使用工具收到的两个精确编号，在一个事务中清理其借阅与资料。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3
                || !args[1].matches("9090[0-9]{10,20}")
                || !args[2].matches("QA-HTTP-[0-9]{10,20}"))
            throw new IllegalArgumentException("仅允许 HTTP 验收生成的精确编号");
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) {
            p.load(reader);
        }
        try (Connection c =
                DriverManager.getConnection(
                        p.getProperty("url"), p.getProperty("user"), p.getProperty("password"))) {
            if (!"library_traditional".equals(c.getCatalog()))
                throw new IllegalArgumentException("仅允许本项目演示库");
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s =
                        c.prepareStatement(
                                "DELETE l FROM loan l JOIN student s ON s.id=l.student_id JOIN book b ON b.id=l.book_id WHERE s.student_no=? AND b.book_no=?")) {
                    s.setString(1, args[1]);
                    s.setString(2, args[2]);
                    s.executeUpdate();
                }
                try (PreparedStatement s =
                        c.prepareStatement("DELETE FROM student WHERE student_no=?")) {
                    s.setString(1, args[1]);
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM book WHERE book_no=?")) {
                    s.setString(1, args[2]);
                    s.executeUpdate();
                }
                c.commit();
                System.out.println("本次 HTTP QA 样本已精确清理。");
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
}
