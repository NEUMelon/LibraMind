package library.model;

/**
 * 学生实体类，保存一名学生的资料。
 * 属性：内部编号、学号、姓名、学院、专业、启用状态和当前已借数量。
 * 方法：各属性的 getter 和 setter。
 */
public class StudentPO {
    private long id;
    private String studentNo, name, college, major;
    private boolean enabled;
    private int borrowedCount;

    /**
     * 获取学生内部编号。
     *
     * @return 学生内部编号
     */
    public long getId() {
        return id;
    }

    /**
     * 设置学生内部编号。
     *
     * @param id 学生内部编号
     */
    public void setId(long id) {
        this.id = id;
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
     * 获取姓名。
     *
     * @return 姓名
     */
    public String getName() {
        return name;
    }

    /**
     * 设置姓名。
     *
     * @param v 姓名
     */
    public void setName(String v) {
        name = v;
    }

    /**
     * 获取学院。
     *
     * @return 学院
     */
    public String getCollege() {
        return college;
    }

    /**
     * 设置学院。
     *
     * @param v 学院
     */
    public void setCollege(String v) {
        college = v;
    }

    /**
     * 获取专业。
     *
     * @return 专业
     */
    public String getMajor() {
        return major;
    }

    /**
     * 设置专业。
     *
     * @param v 专业
     */
    public void setMajor(String v) {
        major = v;
    }

    /**
     * 判断学生资料是否启用。
     *
     * @return true 表示启用，false 表示停用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态。
     *
     * @param v 启用状态
     */
    public void setEnabled(boolean v) {
        enabled = v;
    }

    /**
     * 获取当前未归还图书的册数。
     *
     * @return 当前未归还图书的册数
     */
    public int getBorrowedCount() {
        return borrowedCount;
    }

    /**
     * 设置当前未归还图书的册数。
     *
     * @param v 当前未归还图书的册数
     */
    public void setBorrowedCount(int v) {
        borrowedCount = v;
    }
}
