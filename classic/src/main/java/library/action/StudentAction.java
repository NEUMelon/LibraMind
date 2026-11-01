package library.action;

import library.dao.StudentDAO;
import library.db.DatabaseManager;
import library.model.*;
import library.service.BusinessException;
import library.web.*;
import javax.servlet.http.*;
import java.util.*;

/**
 * 学生页面请求处理类，负责学生列表和资料维护请求。
 * 属性：dao 查询和修改学生资料。
 * 方法：execute 分派页面操作，required 按编号查询学生。
 */
public class StudentAction implements IAction {
    private final StudentDAO dao;

    /**
     * 创建学生请求处理对象。
     *
     * @param db 数据库连接管理器
     */
    public StudentAction(DatabaseManager db) {
        dao = new StudentDAO(db);
    }

    /**
     * 处理学生查询和资料维护请求。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     * @return 要显示的 JSP 文件名；已完成跳转时返回 null
     */
    @Override
    public String execute(HttpServletRequest r, HttpServletResponse s) throws Exception {
        String action = Web.p(r, "action");
        switch (action) {
            case "", "list" -> {
                Web.method(r, "GET");
                Page<StudentPO> page = dao.list(Web.p(r, "q"), Web.p(r, "state"), Web.page(r), 10);
                r.setAttribute("page", page);
                Web.pagination(r, page.getNumber(), page.getPages());
                return "student-list.jsp";
            }
            case "new" -> {
                Web.method(r, "GET");
                Web.form(r, "student-form.jsp");
                return "student-form.jsp";
            }
            case "edit" -> {
                Web.method(r, "GET");
                StudentPO p = required(Web.id(r));
                Web.form(r, "student-form.jsp");
                r.setAttribute("borrowedCount", p.getBorrowedCount());
                r.setAttribute(
                        "v",
                        Map.of(
                                "id",
                                "" + p.getId(),
                                "studentNo",
                                p.getStudentNo(),
                                "name",
                                p.getName(),
                                "college",
                                p.getCollege(),
                                "major",
                                p.getMajor(),
                                "enabled",
                                "" + p.isEnabled()));
                return "student-form.jsp";
            }
            case "save" -> {
                Web.method(r, "POST");
                Web.form(r, "student-form.jsp");
                StudentPO p = new StudentPO();
                boolean edit = !Web.p(r, "id").isEmpty();
                if (edit) {
                    StudentPO old = required(Web.id(r));
                    p.setId(old.getId());
                    p.setStudentNo(old.getStudentNo());
                    r.setAttribute("borrowedCount", old.getBorrowedCount());
                } else p.setStudentNo(Web.field(r, "studentNo", "学号", 32, "[0-9]{1,32}"));
                p.setName(Web.field(r, "name", "姓名", 80, null));
                p.setCollege(Web.field(r, "college", "学院", 120, null));
                p.setMajor(Web.field(r, "major", "专业", 120, null));
                p.setEnabled("true".equals(Web.p(r, "enabled")));
                Web.check(r);
                dao.save(p);
                Web.success(r, s, "students", "学生资料已保存。");
                return null;
            }
            case "delete" -> {
                Web.method(r, "POST");
                Web.success(r, s, "students", dao.delete(Web.id(r)));
                return null;
            }
            default -> throw new BusinessException("不支持的学生操作。");
        }
    }

    /**
     * 按内部编号查询学生。
     *
     * @param id 记录的内部编号
     * @return 学生对象
     * @throws BusinessException 查不到学生时抛出
     */
    private StudentPO required(long id) throws Exception {
        StudentPO p = dao.find(id);
        if (p == null) throw new BusinessException("学生资料不存在。");
        return p;
    }
}
