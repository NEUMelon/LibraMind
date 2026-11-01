package library.action;

import library.dao.LoanDAO;
import library.db.DatabaseManager;
import library.model.*;
import library.service.*;
import library.web.*;
import javax.servlet.http.*;
import java.time.LocalDate;

/**
 * 归还页面请求处理类，负责查询、登记、更正和撤销归还。
 * 属性：dao 查询借阅记录，service 执行归还业务。
 * 方法：execute 分派页面操作，required 按编号查询借阅记录。
 */
public class ReturnAction implements IAction {
    private final LoanDAO dao;
    private final CirculationService service;

    /**
     * 创建归还请求处理对象。
     *
     * @param db 数据库连接管理器
     */
    public ReturnAction(DatabaseManager db) {
        dao = new LoanDAO(db);
        service = new CirculationService(db);
    }

    /**
     * 处理归还查询、登记、更正和撤销请求。
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
                                LoanStatus.RETURNED,
                                from,
                                to,
                                true,
                                Web.page(r),
                                10);
                r.setAttribute("page", page);
                Web.pagination(r, page.getNumber(), page.getPages());
                return "loan-list.jsp";
            }
            case "new" -> {
                Web.method(r, "GET");
                Page<LoanPO> page =
                        dao.list(
                                Web.p(r, "q"),
                                LoanStatus.ACTIVE,
                                null,
                                null,
                                false,
                                Web.page(r),
                                10);
                r.setAttribute("page", page);
                return "return-pick.jsp";
            }
            case "confirm", "edit", "undoForm" -> {
                Web.method(r, "GET");
                String op =
                        switch (Web.p(r, "action")) {
                            case "confirm" -> "return";
                            case "edit" -> "correctReturn";
                            default -> "undo";
                        };
                LoanAction.operationForm(r, required(Web.id(r)), op);
                return "circulation-form.jsp";
            }
            case "return", "correct", "undo" -> {
                Web.method(r, "POST");
                String action = Web.p(r, "action");
                String op = action.equals("correct") ? "correctReturn" : action;
                LoanPO p = required(Web.id(r));
                LoanAction.operationForm(r, p, op);
                switch (action) {
                    case "return" ->
                            service.giveBack(p.getId(), Web.time(r, "at"), Web.p(r, "reason"));
                    case "correct" ->
                            service.correct(p.getId(), Web.time(r, "at"), true, Web.p(r, "reason"));
                    case "undo" -> service.undoReturn(p.getId(), Web.p(r, "reason"));
                }
                Web.success(
                        r,
                        s,
                        "returns",
                        action.equals("undo")
                                ? "误归还已撤销，该册书已恢复在借。"
                                : action.equals("correct") ? "归还日期已更正。" : "归还登记已完成，图书现可再次借阅。");
                return null;
            }
            default -> throw new BusinessException("不支持的归还操作。");
        }
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
}
