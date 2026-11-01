package library.service;

import java.nio.file.*;
import java.util.Optional;

/**
 * 封面文件查询类，负责在本地封面目录中查找图片。
 * 属性：directory 保存封面目录。
 * 方法：读取目录配置、解析 ISBN、检查文件名和查找封面文件。
 */
public final class CoverStorage {
    private final Path directory;

    /**
     * 设置本地封面目录。
     *
     * @param directory 本地封面目录
     */
    public CoverStorage(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /**
     * 读取封面图片目录的配置。
     *
     * @return 封面目录的路径
     */
    public static Path configuredDirectory() {
        String override = System.getProperty("library.covers");
        if (override != null && !override.isBlank()) return Path.of(override);
        String config = System.getProperty("library.config", System.getenv("LIBRARY_CONFIG"));
        if (config == null || config.isBlank())
            throw new IllegalStateException("缺少 library.config，无法定位本地封面目录。");
        return Path.of(config).toAbsolutePath().getParent().getParent().resolve("data/covers");
    }

    /**
     * 从 CN-ISBN13 格式的馆藏书号中提取并校验 ISBN。
     *
     * @param bookNo 馆藏书号
     * @return 有效的 ISBN；无法识别时返回空字符串
     */
    public static String isbnFor(String bookNo) {
        if (bookNo == null || !bookNo.matches("CN-97[89][0-9]{10}")) return "";
        String isbn = bookNo.substring(3);
        int sum = 0;
        for (int i = 0; i < 13; i++) sum += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return sum % 10 == 0 ? isbn : "";
    }

    /**
     * 检查封面文件名是否符合允许的格式。
     *
     * @param path 要检查的封面文件名
     * @return true 表示文件名合法，false 表示不合法
     */
    public static boolean isValidPath(String path) {
        return path != null
                && path.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}\\.(jpg|jpeg|png|webp)");
    }

    /**
     * 按文件名查找本地封面。
     *
     * @param relativePath 相对于封面目录的文件名
     * @return 封面文件路径；未找到时返回 Optional.empty()
     */
    public Optional<Path> find(String relativePath) {
        if (!isValidPath(relativePath)) return Optional.empty();
        Path file = directory.resolve(relativePath);
        try {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0)
                return Optional.of(file);
        } catch (java.io.IOException ignored) {
            /* 文件不可读时按暂无封面处理。 */
        }
        return Optional.empty();
    }
}
