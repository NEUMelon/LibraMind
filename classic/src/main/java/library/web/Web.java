package library.web;

import library.service.BusinessException;
import javax.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Web 请求工具类，封装参数读取、表单校验、分页链接和页面提示。
 * 没有实例属性；所有方法均为静态方法，供 Servlet 和 Action 调用。
 */
public final class Web {
    /**
     * 工具类使用静态方法，无需创建对象。
     */
    private Web() {}

    /**
     * 读取请求参数，去掉首尾空白。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param key 请求参数名
     * @return 参数值；参数不存在时返回空字符串
     */
    public static String p(HttpServletRequest r, String key) {
        String v = r.getParameter(key);
        return v == null ? "" : v.strip();
    }

    /**
     * 将状态筛选参数转换为枚举。
     *
     * @param <E> 枚举类型
     * @param r HTTP 请求，包含页面传入的参数
     * @param key 请求参数名
     * @param type 目标枚举的 Class 对象
     * @return 枚举值；参数为空或无效时返回 null
     */
    public static <E extends Enum<E>> E enumFilter(
            HttpServletRequest r, String key, Class<E> type) {
        try {
            return Enum.valueOf(type, p(r, key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 读取请求中的 id，并检查它是否为正整数。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @return 记录的内部编号
     */
    public static long id(HttpServletRequest r) {
        try {
            long n = Long.parseLong(p(r, "id"));
            if (n > 0) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new BusinessException("记录编号无效。");
    }

    /**
     * 读取请求页码，并将其限制在 1 到 100000 之间。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @return 有效页码；参数缺失或无法解析时返回 1
     */
    public static int page(HttpServletRequest r) {
        try {
            return Math.max(1, Math.min(100000, Integer.parseInt(p(r, "page"))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 判断请求是否为 POST。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @return true 表示 POST，false 表示其他请求方法
     */
    public static boolean post(HttpServletRequest r) {
        return "POST".equals(r.getMethod());
    }

    /**
     * 检查请求使用的 HTTP 方法是否符合要求。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param expected 要求的 HTTP 方法，如 GET 或 POST
     */
    public static void method(HttpServletRequest r, String expected) {
        if (!expected.equals(r.getMethod())) throw new BusinessException("此操作需要通过页面上的表单提交。");
    }

    /**
     * 保存表单输入和页面名称，供校验失败时回显。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param view JSP 文件名
     */
    public static void form(HttpServletRequest r, String view) {
        Map<String, String> v = new LinkedHashMap<>();
        r.getParameterMap().forEach((k, a) -> v.put(k, a[0]));
        r.setAttribute("v", v);
        r.setAttribute("errors", new HashMap<String, String>());
        r.setAttribute("failureView", view);
    }

    /**
     * 读取并校验表单字段，将错误写入表单错误信息。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param key 请求参数名
     * @param label 字段的中文名称
     * @param max 允许的最大字符数
     * @param pattern 格式校验的正则表达式；null 表示不检查格式
     * @return 去掉首尾空白后的字段值
     */
    @SuppressWarnings("unchecked")
    public static String field(
            HttpServletRequest r, String key, String label, int max, String pattern) {
        String value = p(r, key);
        Map<String, String> errors = (Map<String, String>) r.getAttribute("errors");
        if (value.isBlank()) errors.put(key, "请填写" + label);
        else if (value.length() > max) errors.put(key, label + "最多 " + max + " 个字符");
        else if (pattern != null && !value.matches(pattern)) errors.put(key, label + "格式不正确");
        return value;
    }

    /**
     * 检查表单是否存在校验错误，有错误时抛出业务异常。
     *
     * @param r HTTP 请求，包含页面传入的参数
     */
    public static void check(HttpServletRequest r) {
        if (!((Map<?, ?>) r.getAttribute("errors")).isEmpty())
            throw new BusinessException("请检查标记的输入项后再提交。");
    }

    /**
     * 将请求参数转换为日期和时间。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param field 日期时间参数的名称
     * @return 解析后的日期和时间
     */
    public static LocalDateTime time(HttpServletRequest r, String field) {
        try {
            return LocalDateTime.parse(p(r, field));
        } catch (Exception e) {
            throw new BusinessException("请填写有效的日期和时间。");
        }
    }

    /**
     * 将请求参数转换为日期。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param field 日期时间参数的名称
     * @return 解析后的日期；参数为空时返回 null
     */
    public static LocalDate date(HttpServletRequest r, String field) {
        if (p(r, field).isEmpty()) return null;
        try {
            LocalDate d = LocalDate.parse(p(r, field));
            if (d.getYear() < 2000 || d.getYear() > 9998) throw new IllegalArgumentException();
            return d;
        } catch (Exception e) {
            throw new BusinessException("日期筛选格式不正确。");
        }
    }

    /**
     * 将当前时间转换为网页日期时间输入框使用的格式。
     *
     * @return 格式为 yyyy-MM-dd'T'HH:mm 的当前时间
     */
    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    /**
     * 保存成功提示，并跳转到指定模块的列表页。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param s HTTP 响应，用于返回页面或数据
     * @param module 要跳转到的模块名称
     * @param text 成功提示
     */
    public static void success(
            HttpServletRequest r, HttpServletResponse s, String module, String text)
            throws IOException {
        r.getSession().setAttribute("flash", text);
        s.sendRedirect(r.getContextPath() + "/app?module=" + module);
    }

    /**
     * 生成上一页和下一页链接，并保留查询条件。
     *
     * @param r HTTP 请求，包含页面传入的参数
     * @param number 当前页码
     * @param pages 总页数
     */
    public static void pagination(HttpServletRequest r, int number, int pages) {
        StringBuilder base = new StringBuilder(r.getContextPath() + "/app?");
        for (String key : List.of("module", "q", "state", "from", "to", "studentNo")) {
            String val = p(r, key);
            if (!val.isEmpty())
                base.append(key)
                        .append('=')
                        .append(URLEncoder.encode(val, StandardCharsets.UTF_8))
                        .append('&');
        }
        r.setAttribute("previous", base + "page=" + Math.max(1, number - 1));
        r.setAttribute("next", base + "page=" + Math.min(pages, number + 1));
    }

    /**
     * 将异常转换为页面显示的错误提示。
     *
     * @param e 捕获到的异常
     * @return 供用户阅读的错误提示
     */
    public static String message(Exception e) {
        if (e instanceof BusinessException) return e.getMessage();
        if (e instanceof java.sql.SQLException sql) {
            if (sql.getErrorCode() == 1062) return "编号已存在，请换一个学号或书号；借阅时请确认图书尚未借出。";
            if ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1205)
                return "其他操作正在修改这份资料，请稍后重试。";
        }
        return "本次操作未完成，请稍后重试；详细原因已记录在服务器日志中。";
    }
}
