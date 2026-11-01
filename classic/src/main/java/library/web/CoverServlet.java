package library.web;

import library.service.CoverStorage;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.nio.file.*;

/**
 * 封面图片请求处理类，根据请求的文件名返回本地图片。
 * 属性：storage 查找封面文件。
 * 方法：init 初始化封面目录，doGet 返回封面；缺图时返回占位图。
 */
@WebServlet("/covers/*")
public final class CoverServlet extends HttpServlet {
    private CoverStorage storage;

    /**
     * 创建本地封面查询对象。
     */
    @Override
    public void init() {
        storage = new CoverStorage(CoverStorage.configuredDirectory());
    }

    /**
     * 返回请求的封面图片；缺图时返回占位图。
     *
     * @param request HTTP 请求，包含页面传入的参数
     * @param response HTTP 响应，用于返回页面或数据
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.startsWith("/") || !CoverStorage.isValidPath(path.substring(1))) {
            response.sendError(404);
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "public, max-age=3600");
        String relativePath = path.substring(1);
        var file = storage.find(relativePath);
        // 文件可能在页面渲染后被移走；此时同样显示占位图，不输出服务器路径。
        if (file.isPresent()) {
            try (InputStream in = Files.newInputStream(file.get())) {
                response.setContentType(
                        relativePath.endsWith(".png")
                                ? "image/png"
                                : relativePath.endsWith(".webp") ? "image/webp" : "image/jpeg");
                in.transferTo(response.getOutputStream());
                return;
            } catch (NoSuchFileException ignored) {
            }
        }
        response.setContentType("image/svg+xml;charset=UTF-8");
        try (InputStream in =
                getServletContext().getResourceAsStream("/assets/cover-missing.svg")) {
            if (in == null) {
                response.sendError(404);
                return;
            }
            in.transferTo(response.getOutputStream());
        }
    }
}
