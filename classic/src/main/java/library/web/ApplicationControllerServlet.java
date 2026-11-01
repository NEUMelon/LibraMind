package library.web;

import library.db.DatabaseManager;
import library.dao.*;
import library.action.*;
import library.service.BusinessException;
import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 页面请求入口，接收请求并交给对应的 Action 处理。
 * 属性：actions 保存模块与 Action 的对应关系，loans 查询首页数据。
 * 方法：初始化模块、处理 GET 和 POST 请求、分派请求和显示 JSP 页面。
 */
@WebServlet(urlPatterns = {"", "/app"})
public class ApplicationControllerServlet extends HttpServlet {
    private Map<String, IAction> actions;
    private LoanDAO loans;

    /**
     * 创建各模块的 Action 和首页查询对象。
     */
    @Override
    public void init() {
        DatabaseManager db = (DatabaseManager) getServletContext().getAttribute("database");
        loans = new LoanDAO(db);
        actions =
                Map.of(
                        "students",
                        new StudentAction(db),
                        "books",
                        new BookAction(db),
                        "loans",
                        new LoanAction(db),
                        "returns",
                        new ReturnAction(db));
    }

    /**
     * 处理页面 GET 请求。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     */
    @Override
    protected void doGet(HttpServletRequest r, HttpServletResponse s)
            throws ServletException, IOException {
        handle(r, s);
    }

    /**
     * 处理表单 POST 请求。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     */
    @Override
    protected void doPost(HttpServletRequest r, HttpServletResponse s)
            throws ServletException, IOException {
        handle(r, s);
    }

    /**
     * 检查请求并交给对应模块处理。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     */
    private void handle(HttpServletRequest r, HttpServletResponse s)
            throws ServletException, IOException {
        r.setCharacterEncoding("UTF-8");
        s.setContentType("text/html;charset=UTF-8");
        s.setHeader("X-Content-Type-Options", "nosniff");
        s.setHeader("X-Frame-Options", "DENY");
        s.setHeader("Cache-Control", "no-store");
        s.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
        HttpSession session = r.getSession();
        if (session.getAttribute("csrf") == null) {
            byte[] token = new byte[32];
            new SecureRandom().nextBytes(token);
            session.setAttribute("csrf", HexFormat.of().formatHex(token));
        }
        String module = Web.p(r, "module");
        if (module.isEmpty()) module = "home";
        if (!module.equals("home") && !actions.containsKey(module)) {
            s.setStatus(404);
            r.setAttribute("error", "没有这个功能页面。");
            render(r, s, "error.jsp");
            return;
        }
        r.setAttribute("module", module);
        r.setAttribute("ctx", r.getContextPath());
        r.setAttribute("today", java.time.LocalDate.now());
        Map<String, String> titles =
                Map.of(
                        "home",
                        "工作台",
                        "students",
                        "学生管理",
                        "books",
                        "图书管理",
                        "loans",
                        "借书记录",
                        "returns",
                        "还书记录");
        r.setAttribute("title", titles.get(module));
        synchronized (session) {
            Object flash = session.getAttribute("flash");
            if (flash != null) {
                r.setAttribute("flash", flash);
                session.removeAttribute("flash");
            }
        }
        if (Web.post(r)
                && !MessageDigest.isEqual(
                        ((String) session.getAttribute("csrf")).getBytes(StandardCharsets.UTF_8),
                        Web.p(r, "csrf").getBytes(StandardCharsets.UTF_8))) {
            s.setStatus(403);
            r.setAttribute("error", "页面校验已失效，请重新打开表单后提交。");
            render(r, s, "error.jsp");
            return;
        }
        String view;
        try {
            if (module.equals("home")) {
                Web.method(r, "GET");
                r.setAttribute("stats", loans.statistics());
                r.setAttribute("recent", loans.list("", null, null, null, false, 1, 5).getItems());
                r.setAttribute(
                        "shelf",
                        new BookDAO((DatabaseManager) getServletContext().getAttribute("database"))
                                .list("", library.model.BookStatus.AVAILABLE, 1, 4)
                                .getItems());
                view = "home.jsp";
            } else view = actions.get(module).execute(r, s);
        } catch (Exception e) {
            if (!(e instanceof BusinessException))
                getServletContext().log("Library request failed: module=" + module, e);
            r.setAttribute("error", Web.message(e));
            s.setStatus(
                    e instanceof BusinessException
                            ? 422
                            : e instanceof java.sql.SQLException sql && sql.getErrorCode() == 1062
                                    ? 409
                                    : 500);
            view = (String) r.getAttribute("failureView");
            if (view == null) view = "error.jsp";
        }
        if (view != null) render(r, s, view);
    }

    /**
     * 将请求转发到指定 JSP 页面。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     * @param view JSP 文件名
     */
    private void render(HttpServletRequest r, HttpServletResponse s, String view)
            throws ServletException, IOException {
        r.getRequestDispatcher("/WEB-INF/views/" + view).forward(r, s);
    }
}
