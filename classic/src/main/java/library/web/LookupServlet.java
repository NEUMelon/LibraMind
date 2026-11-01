package library.web;

import library.dao.*;
import library.db.DatabaseManager;
import library.model.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * 借阅登记中的学生和图书查询接口，返回 JSON 数据。
 * 属性：students 和 books 查询资料，PAGE_SIZE 指定每页 10 条。
 * 方法：分页查询、按完整编号查询，以及生成 JSON 响应。
 */
@WebServlet("/lookup")
public final class LookupServlet extends HttpServlet {
    private StudentDAO students;
    private BookDAO books;
    private static final int PAGE_SIZE = 10;

    /**
     * 创建学生和图书查询对象。
     */
    @Override
    public void init() {
        DatabaseManager db = (DatabaseManager) getServletContext().getAttribute("database");
        students = new StudentDAO(db);
        books = new BookDAO(db);
    }

    /**
     * 查询学生或图书，将查询结果写为 JSON 响应。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     */
    @Override
    protected void doGet(HttpServletRequest r, HttpServletResponse s) throws IOException {
        r.setCharacterEncoding("UTF-8");
        s.setContentType("application/json;charset=UTF-8");
        s.setHeader("Cache-Control", "no-store");
        s.setHeader("X-Content-Type-Options", "nosniff");
        String kind = Web.p(r, "kind"), query = Web.p(r, "q"), code = Web.p(r, "code");
        if (!Set.of("student", "book").contains(kind)
                || query.length() > 120
                || code.length() > 40) {
            error(s, 400, "查询条件不正确。");
            return;
        }
        try {
            if (r.getParameter("code") != null) {
                if (!code.matches(kind.equals("student") ? "[0-9]{1,32}" : "[A-Za-z0-9_-]{1,40}")) {
                    error(s, 400, "请输入完整的" + (kind.equals("student") ? "学号。" : "馆藏书号。"));
                    return;
                }
                exact(s, kind, code);
                return;
            }
            if (kind.equals("student")) {
                Page<StudentPO> page = students.list(query, "enabled", Web.page(r), PAGE_SIZE);
                List<String> rows = new ArrayList<>();
                for (StudentPO p : page.getItems())
                    rows.add(item(p.getStudentNo(), p.getName(), p.getCollege(), p.getMajor()));
                page(s, page, rows);
            } else {
                Page<BookPO> page = books.list(query, BookStatus.AVAILABLE, Web.page(r), PAGE_SIZE);
                List<String> rows = new ArrayList<>();
                for (BookPO p : page.getItems())
                    rows.add(item(p.getBookNo(), p.getTitle(), p.getAuthor(), p.getEdition()));
                page(s, page, rows);
            }
        } catch (SQLException e) {
            getServletContext().log("Borrow lookup failed", e);
            error(s, 503, "暂时无法查询，请稍后重试。");
        }
    }

    /**
     * 按完整学号或书号查询，并返回是否可借及资料说明。
     *
     * @param s HTTP 响应，用于返回页面或数据
     * @param kind 查询类型，student 表示学生，book 表示图书
     * @param code 完整学号或馆藏书号
     */
    private void exact(HttpServletResponse s, String kind, String code)
            throws SQLException, IOException {
        boolean found, eligible;
        String label = "", message;
        if (kind.equals("student")) {
            StudentPO p = students.findByNumber(code);
            found = p != null;
            eligible = found && p.isEnabled();
            if (found) label = String.join("／", p.getName(), p.getCollege(), p.getMajor());
            message = !found ? "未找到该学号，请核对或选择学生。" : !eligible ? "该学生已停用，无法登记借阅。" : "";
        } else {
            BookPO p = books.findByNumber(code);
            found = p != null;
            eligible = found && p.getStatus() == BookStatus.AVAILABLE;
            if (found) label = String.join("／", p.getTitle(), p.getAuthor(), p.getEdition());
            message =
                    !found
                            ? "未找到该馆藏书号，请核对或选择图书。"
                            : eligible
                                    ? ""
                                    : p.getStatus() == BookStatus.BORROWED
                                            ? "这册图书正在借阅中，暂不可借。"
                                            : "这册图书已下架，暂不可借。";
        }
        s.getWriter()
                .write(
                        "{\"found\":"
                                + found
                                + ",\"eligible\":"
                                + eligible
                                + ",\"code\":"
                                + json(code)
                                + ",\"label\":"
                                + json(label)
                                + ",\"message\":"
                                + json(message)
                                + "}");
    }

    /**
     * 将一条学生或图书资料转换为选择列表中的 JSON 记录。
     *
     * @param code 完整学号或馆藏书号
     * @param name 学生姓名或图书书名
     * @param secondary 学生学院或图书作者
     * @param extra 学生专业或图书版本
     * @return 一条记录的 JSON 字符串
     */
    private static String item(String code, String name, String secondary, String extra) {
        return "{\"code\":"
                + json(code)
                + ",\"label\":"
                + json(String.join("／", name, secondary, extra))
                + ",\"cells\":["
                + String.join(",", json(code), json(name), json(secondary), json(extra))
                + "]}";
    }

    /**
     * 将分页查询结果写为 JSON 响应。
     *
     * @param s HTTP 响应，用于返回页面或数据
     * @param page 当前页数据和分页信息
     * @param rows 已转换为 JSON 的当前页记录
     */
    private static void page(HttpServletResponse s, Page<?> page, List<String> rows)
            throws IOException {
        s.getWriter()
                .write(
                        "{\"total\":"
                                + page.getTotal()
                                + ",\"page\":"
                                + page.getNumber()
                                + ",\"pages\":"
                                + page.getPages()
                                + ",\"items\":["
                                + String.join(",", rows)
                                + "]}");
    }

    /**
     * 返回指定 HTTP 状态码和 JSON 错误提示。
     *
     * @param s HTTP 响应，用于返回页面或数据
     * @param status HTTP 状态码
     * @param message 错误提示
     */
    private static void error(HttpServletResponse s, int status, String message)
            throws IOException {
        s.setStatus(status);
        s.getWriter().write("{\"message\":" + json(message) + "}");
    }

    /**
     * 转义字符串，并在两端加上 JSON 字符串所需的双引号。
     *
     * @param value 原始字符串
     * @return 转义后的 JSON 字符串
     */
    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
}
