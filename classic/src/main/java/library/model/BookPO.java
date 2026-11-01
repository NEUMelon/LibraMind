package library.model;

/**
 * 图书实体类，保存一册馆藏图书的资料。
 * 属性：内部编号、馆藏书号、书名、作者、版本、状态和封面文件名。
 * 方法：各属性的 getter 和 setter。
 */
public class BookPO {
    private long id;
    private String bookNo, title, author, edition, coverPath;
    private BookStatus status;

    /**
     * 获取图书内部编号。
     *
     * @return 图书内部编号
     */
    public long getId() {
        return id;
    }

    /**
     * 设置图书内部编号。
     *
     * @param v 图书内部编号
     */
    public void setId(long v) {
        id = v;
    }

    /**
     * 获取馆藏书号。
     *
     * @return 馆藏书号
     */
    public String getBookNo() {
        return bookNo;
    }

    /**
     * 设置馆藏书号。
     *
     * @param v 馆藏书号
     */
    public void setBookNo(String v) {
        bookNo = v;
    }

    /**
     * 获取书名。
     *
     * @return 书名
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置书名。
     *
     * @param v 书名
     */
    public void setTitle(String v) {
        title = v;
    }

    /**
     * 获取作者。
     *
     * @return 作者
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 设置作者。
     *
     * @param v 作者
     */
    public void setAuthor(String v) {
        author = v;
    }

    /**
     * 获取版本说明。
     *
     * @return 版本说明
     */
    public String getEdition() {
        return edition;
    }

    /**
     * 设置版本说明。
     *
     * @param v 版本说明
     */
    public void setEdition(String v) {
        edition = v;
    }

    /**
     * 获取图书状态。
     *
     * @return 图书状态
     */
    public BookStatus getStatus() {
        return status;
    }

    /**
     * 设置图书状态。
     *
     * @param v 图书状态
     */
    public void setStatus(BookStatus v) {
        status = v;
    }

    /**
     * 获取相对于封面目录的文件名。
     *
     * @return 相对于封面目录的文件名；未关联封面时为 null
     */
    public String getCoverPath() {
        return coverPath;
    }

    /**
     * 设置相对于封面目录的文件名。
     *
     * @param v 相对于封面目录的文件名
     */
    public void setCoverPath(String v) {
        coverPath = v;
    }
}
