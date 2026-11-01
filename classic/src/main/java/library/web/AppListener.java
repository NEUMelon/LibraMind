package library.web;

import library.db.DatabaseManager;
import javax.servlet.*;
import javax.servlet.annotation.WebListener;
import java.nio.file.Path;

/**
 * 应用启动和停止的监听器。
 * 方法：contextInitialized 创建数据库连接管理器，contextDestroyed 关闭连接池。
 */
@WebListener
public class AppListener implements ServletContextListener {
    /**
     * 在应用启动时创建数据库连接管理器。
     *
     * @param e 应用启动或停止事件
     */
    @Override
    public void contextInitialized(ServletContextEvent e) {
        String file = System.getProperty("library.config", System.getenv("LIBRARY_CONFIG"));
        if (file == null || file.isBlank())
            throw new IllegalStateException("请通过 -Dlibrary.config 指定外部数据库配置。");
        try {
            e.getServletContext().setAttribute("database", new DatabaseManager(Path.of(file)));
        } catch (Exception ex) {
            throw new IllegalStateException("数据库初始化失败，请检查本地配置和 MySQL 服务。", ex);
        }
    }

    /**
     * 在应用停止时关闭数据库连接池。
     *
     * @param e 应用启动或停止事件
     */
    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Object db = e.getServletContext().getAttribute("database");
        if (db instanceof DatabaseManager d) d.close();
    }
}
