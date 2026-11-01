package library.web;

import javax.servlet.http.*;

/**
 * 页面模块的请求处理接口。
 * 方法：execute 处理请求并返回要显示的 JSP 文件名。
 */
public interface IAction {
    /**
     * 处理当前模块的页面请求。
     *
     * @param request HTTP 请求，包含页面传入的参数
     * @param response HTTP 响应，用于返回页面或数据
     * @return 要显示的 JSP 文件名；已完成跳转时返回 null
     */
    String execute(HttpServletRequest request, HttpServletResponse response) throws Exception;
}
