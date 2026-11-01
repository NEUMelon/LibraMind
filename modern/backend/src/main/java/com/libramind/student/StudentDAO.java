package com.libramind.student;

import static com.libramind.common.db.SqlStore.*;

import com.libramind.common.api.PageResult;
import com.libramind.common.db.SqlStore;
import com.libramind.student.StudentDtos.StudentInput;
import com.libramind.student.StudentDtos.StudentView;

import org.springframework.stereotype.Repository;

import java.util.Map;

/** 学生数据访问，负责分页、精确查询和资料写入，借阅数量由 SQL 统计。 */
@Repository
public class StudentDAO {

    private static final String SELECT =
            """
SELECT s.*, (SELECT COUNT(*) FROM loan l WHERE l.student_id=s.id AND l.status='ACTIVE') AS borrowed_count
FROM student s
""";
    private final SqlStore sql;

    public StudentDAO(SqlStore sql) {
        this.sql = sql;
    }

    /** 按学号、姓名、学院、专业查询；state 为空时包含启用和停用资料。 */
    public PageResult<StudentView> list(String query, String state, int page, int size) {
        PageResult.validate(page, size);
        String where =
                " WHERE (s.student_no LIKE ? OR s.name LIKE ? OR s.college LIKE ? OR s.major LIKE"
                        + " ?)";
        if ("enabled".equals(state)) {
            where += " AND s.enabled=TRUE";
        } else if ("disabled".equals(state)) {
            where += " AND s.enabled=FALSE";
        }
        String q = "%" + query + "%";
        long total = sql.count("SELECT COUNT(*) FROM student s" + where, q, q, q, q);
        return PageResult.of(
                sql
                        .list(
                                SELECT + where + " ORDER BY s.student_no LIMIT ? OFFSET ?",
                                q,
                                q,
                                q,
                                q,
                                size,
                                (page - 1L) * size)
                        .stream()
                        .map(this::view)
                        .toList(),
                page,
                size,
                total);
    }

    /** 按数据库主键查询，未找到返回 null。 */
    public StudentView find(long id) {
        Map<String, Object> row = sql.one(SELECT + " WHERE s.id=?", id);
        return row == null ? null : view(row);
    }

    /** 按学号精确查询，未找到返回 null。 */
    public StudentView findByNumber(String number) {
        Map<String, Object> row = sql.one(SELECT + " WHERE s.student_no=?", number);
        return row == null ? null : view(row);
    }

    public long insert(StudentInput input) {
        return sql.insert(
                "INSERT INTO student(student_no,name,college,major,enabled) VALUES(?,?,?,?,?)",
                input.studentNo(),
                input.name(),
                input.college(),
                input.major(),
                input.enabled());
    }

    public void update(long id, StudentInput input) {
        sql.update(
                "UPDATE student SET student_no=?,name=?,college=?,major=?,enabled=? WHERE id=?",
                input.studentNo(),
                input.name(),
                input.college(),
                input.major(),
                input.enabled(),
                id);
    }

    private StudentView view(Map<String, Object> row) {
        return new StudentView(
                number(row, "id"),
                text(row, "student_no"),
                text(row, "name"),
                text(row, "college"),
                text(row, "major"),
                bool(row, "enabled"),
                number(row, "borrowed_count"));
    }
}
