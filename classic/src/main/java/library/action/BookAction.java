package library.action;

import library.dao.BookDAO;
import library.db.DatabaseManager;
import library.model.*;
import library.service.BusinessException;
import library.service.CoverStorage;
import library.web.*;
import javax.servlet.http.*;
import java.util.*;

/**
 * 图书页面请求处理类，负责图书列表、详情和资料维护请求。
 * 属性：dao 查询和修改图书资料。
 * 方法：execute 分派页面操作，required 按编号查询图书。
 */
public class BookAction implements IAction {
    private final BookDAO dao;

    /**
     * 创建图书请求处理对象。
     *
     * @param db 数据库连接管理器
     */
    public BookAction(DatabaseManager db) {
        dao = new BookDAO(db);
    }

    /**
     * 处理图书查询、详情和资料维护请求。
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
                Page<BookPO> page =
                        dao.list(
                                Web.p(r, "q"),
                                Web.enumFilter(r, "state", BookStatus.class),
                                Web.page(r),
                                8);
                r.setAttribute("page", page);
                Web.pagination(r, page.getNumber(), page.getPages());
                return "book-list.jsp";
            }
            case "detail" -> {
                Web.method(r, "GET");
                BookPO book = required(Web.id(r));
                String isbn = CoverStorage.isbnFor(book.getBookNo());
                boolean available =
                        new CoverStorage(CoverStorage.configuredDirectory())
                                .find(book.getCoverPath())
                                .isPresent();
                r.setAttribute("book", book);
                r.setAttribute("isbn", isbn);
                r.setAttribute("hasCover", available);
                r.setAttribute(
                        "coverUrl",
                        r.getContextPath()
                                + (available
                                        ? "/covers/" + book.getCoverPath()
                                        : "/assets/cover-missing.svg"));
                r.setAttribute(
                        "coverSourceUrl",
                        available && book.getCoverPath().matches("97[89][0-9]{10}\\.jpg")
                                ? "https://openlibrary.org/isbn/"
                                        + book.getCoverPath().substring(0, 13)
                                : "");
                r.setAttribute("title", "图书详情");
                return "book-detail.jsp";
            }
            case "new" -> {
                Web.method(r, "GET");
                Web.form(r, "book-form.jsp");
                return "book-form.jsp";
            }
            case "edit" -> {
                Web.method(r, "GET");
                BookPO p = required(Web.id(r));
                Web.form(r, "book-form.jsp");
                r.setAttribute(
                        "v",
                        Map.of(
                                "id",
                                "" + p.getId(),
                                "bookNo",
                                p.getBookNo(),
                                "bookTitle",
                                p.getTitle(),
                                "author",
                                p.getAuthor(),
                                "edition",
                                p.getEdition(),
                                "status",
                                p.getStatus().name()));
                return "book-form.jsp";
            }
            case "save" -> {
                Web.method(r, "POST");
                Web.form(r, "book-form.jsp");
                BookPO p = new BookPO();
                if (!Web.p(r, "id").isEmpty()) {
                    BookPO old = required(Web.id(r));
                    p.setId(old.getId());
                    p.setBookNo(old.getBookNo());
                    p.setCoverPath(old.getCoverPath());
                } else p.setBookNo(Web.field(r, "bookNo", "书号", 40, "[A-Za-z0-9_-]{1,40}"));
                p.setTitle(Web.field(r, "bookTitle", "书名", 200, null));
                p.setAuthor(Web.field(r, "author", "作者", 200, null));
                p.setEdition(Web.field(r, "edition", "版本号", 80, null));
                if (p.getId() == 0) {
                    p.setStatus(BookStatus.AVAILABLE);
                } else {
                    try {
                        p.setStatus(BookStatus.valueOf(Web.p(r, "status")));
                    } catch (IllegalArgumentException e) {
                        throw new BusinessException("请选择有效的馆藏状态。");
                    }
                }
                Web.check(r);
                dao.save(p);
                Web.success(r, s, "books", "图书资料已保存。");
                return null;
            }
            case "delete" -> {
                Web.method(r, "POST");
                Web.success(r, s, "books", dao.delete(Web.id(r)));
                return null;
            }
            default -> throw new BusinessException("不支持的图书操作。");
        }
    }

    /**
     * 按内部编号查询图书。
     *
     * @param id 记录的内部编号
     * @return 图书对象
     * @throws BusinessException 查不到图书时抛出
     */
    private BookPO required(long id) throws Exception {
        BookPO p = dao.find(id);
        if (p == null) throw new BusinessException("图书资料不存在。");
        return p;
    }
}
