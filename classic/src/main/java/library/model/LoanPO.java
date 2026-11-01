package library.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 借阅记录实体类，保存一次借阅的信息。
 * 属性：记录编号、学生和图书信息、借还时间、状态、备注，以及日期显示格式 DISPLAY。
 * 方法：各属性的 getter、setter，以及借还时间的格式化方法。
 */
public class LoanPO {
    private long id, studentId, bookId;
    private String studentNo, studentName, bookNo, bookTitle, note;
    private LoanStatus status;
    private LocalDateTime borrowedAt, returnedAt;
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 获取借阅记录内部编号。
     *
     * @return 借阅记录内部编号
     */
    public long getId() {
        return id;
    }

    /**
     * 设置借阅记录内部编号。
     *
     * @param v 借阅记录内部编号
     */
    public void setId(long v) {
        id = v;
    }

    /**
     * 获取学生内部编号。
     *
     * @return 学生内部编号
     */
    public long getStudentId() {
        return studentId;
    }

    /**
     * 设置学生内部编号。
     *
     * @param v 学生内部编号
     */
    public void setStudentId(long v) {
        studentId = v;
    }

    /**
     * 获取图书内部编号。
     *
     * @return 图书内部编号
     */
    public long getBookId() {
        return bookId;
    }

    /**
     * 设置图书内部编号。
     *
     * @param v 图书内部编号
     */
    public void setBookId(long v) {
        bookId = v;
    }

    /**
     * 获取学号。
     *
     * @return 学号
     */
    public String getStudentNo() {
        return studentNo;
    }

    /**
     * 设置学号。
     *
     * @param v 学号
     */
    public void setStudentNo(String v) {
        studentNo = v;
    }

    /**
     * 获取学生姓名。
     *
     * @return 学生姓名
     */
    public String getStudentName() {
        return studentName;
    }

    /**
     * 设置学生姓名。
     *
     * @param v 学生姓名
     */
    public void setStudentName(String v) {
        studentName = v;
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
    public String getBookTitle() {
        return bookTitle;
    }

    /**
     * 设置书名。
     *
     * @param v 书名
     */
    public void setBookTitle(String v) {
        bookTitle = v;
    }

    /**
     * 获取借阅状态。
     *
     * @return 借阅状态
     */
    public LoanStatus getStatus() {
        return status;
    }

    /**
     * 设置借阅状态。
     *
     * @param v 借阅状态
     */
    public void setStatus(LoanStatus v) {
        status = v;
    }

    /**
     * 获取备注。
     *
     * @return 备注
     */
    public String getNote() {
        return note;
    }

    /**
     * 设置备注。
     *
     * @param v 备注
     */
    public void setNote(String v) {
        note = v;
    }

    /**
     * 获取借阅时间。
     *
     * @return 借阅时间
     */
    public LocalDateTime getBorrowedAt() {
        return borrowedAt;
    }

    /**
     * 设置借阅时间。
     *
     * @param v 借阅时间
     */
    public void setBorrowedAt(LocalDateTime v) {
        borrowedAt = v;
    }

    /**
     * 获取归还时间。
     *
     * @return 归还时间；尚未归还时为 null
     */
    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    /**
     * 设置归还时间。
     *
     * @param v 归还时间
     */
    public void setReturnedAt(LocalDateTime v) {
        returnedAt = v;
    }

    /**
     * 将借阅时间转换为页面显示文字。
     *
     * @return 格式为 yyyy-MM-dd HH:mm 的时间文字
     */
    public String getBorrowedText() {
        return borrowedAt.format(DISPLAY);
    }

    /**
     * 将归还时间转换为页面显示文字。
     *
     * @return 格式为 yyyy-MM-dd HH:mm 的时间文字；尚未归还时返回“未归还”
     */
    public String getReturnedText() {
        return returnedAt == null ? "未归还" : returnedAt.format(DISPLAY);
    }
}
