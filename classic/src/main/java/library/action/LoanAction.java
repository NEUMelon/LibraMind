package library.action;

import library.dao.*;
import library.db.DatabaseManager;
import library.model.*;
import library.service.*;
import library.web.*;
import javax.servlet.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 借阅页面请求处理类，负责查询、登记、更正和作废借阅。
 * 属性：dao 查询借阅记录，service 执行借还业务。
 * 方法：分派请求、准备借阅表单、按编号查询记录和准备操作确认表单。
 */
public class LoanAction implements IAction {
    private final LoanDAO dao;
    private final CirculationService service;

    /**
     * 创建借阅请求处理对象。
     *
     * @param db 数据库连接管理器
     */
    public LoanAction(DatabaseManager db) {
        dao = new LoanDAO(db);
        service = new CirculationService(db);
    }

    /**
     * 处理借阅查询、登记、更正和作废请求。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     * @return 要显示的 JSP 文件名；已完成跳转时返回 null
     */
    @Override
    public String execute(HttpServletRequest r, HttpServletResponse s) throws Exception {
        switch (Web.p(r, "action")) {
            case "", "list" -> {
                Web.method(r, "GET");
                LocalDate from = Web.date(r, "from"), to = Web.date(r, "to");
                if (from != null && to != null && from.isAfter(to))
                    throw new BusinessException("开始日期不能晚于结束日期。");
                Page<LoanPO> page =
                        dao.list(
                                Web.p(r, "q"),
                                Web.enumFilter(r, "state", LoanStatus.class),
                                from,
                                to,
                                false,
                                Web.page(r),
                                10,
                                Web.p(r, "studentNo"));
                r.setAttribute("page", page);
                Web.pagination(r, page.getNumber(), page.getPages());
                return "loan-list.jsp";
            }
            case "new" -> {
                Web.method(r, "GET");
                borrowForm(r);
                return "borrow-form.jsp";
            }
            case "borrow" -> {
                Web.method(r, "POST");
                borrowForm(r);
                String no = Web.field(r, "studentNo", "学号", 32, "[0-9]{1,32}");
                String book = Web.field(r, "bookNo", "书号", 40, "[A-Za-z0-9_-]{1,40}");
                Web.check(r);
                service.borrow(no, book, Web.time(r, "at"), Web.p(r, "reason"));
                Web.success(r, s, "loans", "借阅登记成功，图书已标记为在借。");
                return null;
            }
            case "edit", "cancelForm" -> {
                Web.method(r, "GET");
                String op = Web.p(r, "action").equals("edit") ? "correctBorrow" : "cancel";
                operationForm(r, required(Web.id(r)), op);
                return "circulation-form.jsp";
            }
            case "correct", "cancel" -> {
                Web.method(r, "POST");
                String op = Web.p(r, "action").equals("correct") ? "correctBorrow" : "cancel";
                LoanPO p = required(Web.id(r));
                operationForm(r, p, op);
                if (op.equals("cancel")) service.cancel(p.getId(), Web.p(r, "reason"));
                else service.correct(p.getId(), Web.time(r, "at"), false, Web.p(r, "reason"));
                Web.success(
                        r, s, "loans", op.equals("cancel") ? "误登记已作废，馆藏状态已按当前记录正确处理。" : "借阅日期已更正。");
                return null;
            }
            default -> throw new BusinessException("不支持的借阅操作。");
        }
    }

    /**
     * 准备登记借阅页面需要的表单数据。
     *
     * @param r HTTP 请求，包含页面传入的参数
     */
    private void borrowForm(HttpServletRequest r) throws Exception {
        Web.form(r, "borrow-form.jsp");
        if (!Web.post(r))
            r.setAttribute(
                    "v",
                    Map.of(
                            "studentNo",
                            Web.p(r, "studentNo"),
                            "bookNo",
                            Web.p(r, "bookNo"),
                            "at",
                            Web.now()));
    }

    /**
     * 按内部编号查询借阅记录。
     *
     * @param id 记录的内部编号
     * @return 借阅记录对象
     * @throws BusinessException 查不到借阅记录时抛出
     */
    private LoanPO required(long id) throws Exception {
        LoanPO p = dao.find(id);
        if (p == null) throw new BusinessException("借阅记录不存在。");
        return p;
    }

    /**
     * 准备归还、更正、作废或撤销操作的确认表单。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param p 当前借阅记录
     * @param op 操作类型：return、cancel、undo、correctBorrow 或 correctReturn
     */
    public static void operationForm(HttpServletRequest r, LoanPO p, String op) {
        Web.form(r, "circulation-form.jsp");
        r.setAttribute("loan", p);
        r.setAttribute("operation", op);
        if (!Web.post(r)) {
            LocalDateTime date =
                    op.equals("correctBorrow")
                            ? p.getBorrowedAt()
                            : op.equals("correctReturn") ? p.getReturnedAt() : LocalDateTime.now();
            r.setAttribute(
                    "v",
                    Map.of(
                            "at",
                            date == null
                                    ? Web.now()
                                    : date.format(
                                            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")),
                            "reason",
                            ""));
        }
    }
}
