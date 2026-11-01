package com.libramind.student;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.service.ActorService;
import com.libramind.common.api.BusinessException;
import com.libramind.common.api.PageResult;
import com.libramind.common.db.AuditService;
import com.libramind.common.db.SqlStore;
import com.libramind.student.StudentDtos.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学生管理业务，检查管理员权限、历史关联和账号创建条件，并保存审计。 */
@Service
public class StudentService {
    /** 管理员按完整学号查询；没有匹配记录时返回不存在。 */
    public StudentView findByNumber(CurrentUser actor, String number) {
        ActorService.requireAdmin(actor);
        var found = students.findByNumber(number);
        if (found == null) {
            throw BusinessException.notFound();
        }
        return found;
    }

    private final StudentDAO students;
    private final SqlStore sql;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    public StudentService(
            StudentDAO students, SqlStore sql, AuditService audit, PasswordEncoder encoder) {
        this.students = students;
        this.sql = sql;
        this.audit = audit;
        this.encoder = encoder;
    }

    public PageResult<StudentView> list(
            CurrentUser actor, String q, String state, int page, int size) {
        ActorService.requireAdmin(actor);
        return students.list(q, state, page, size);
    }

    public StudentView find(CurrentUser actor, long id) {
        ActorService.requireOwner(actor, id);
        StudentView found = students.find(id);
        if (found == null) {
            throw BusinessException.notFound();
        }
        return found;
    }

    @Transactional
    public StudentView create(CurrentUser actor, StudentInput input) {
        ActorService.requireAdmin(actor);
        long id = students.insert(input);
        StudentView result = students.find(id);
        audit.record(actor, "STUDENT_CREATE", "STUDENT", id, null, result, "新增学生");
        return result;
    }

    @Transactional
    public StudentView update(CurrentUser actor, long id, StudentInput input) {
        ActorService.requireAdmin(actor);
        lock(id);
        StudentView before = find(actor, id);
        students.update(id, input);
        StudentView after = students.find(id);
        audit.record(actor, "STUDENT_UPDATE", "STUDENT", id, before, after, "修改学生资料");
        return after;
    }

    /** 有在借记录时拒绝删除；有历史或账号时停用，无任何关联时物理删除。 */
    @Transactional
    public String delete(CurrentUser actor, long id, String reason) {
        ActorService.requireAdmin(actor);
        requireReason(reason);
        lock(id);
        StudentView before = find(actor, id);
        if (before.currentBorrowedCount() > 0) {
            throw BusinessException.conflict("STUDENT_HAS_ACTIVE_LOANS", "学生有未归还图书，不能删除");
        }
        boolean keep =
                sql.count("SELECT COUNT(*) FROM loan WHERE student_id=?", id) > 0
                        || sql.count("SELECT COUNT(*) FROM user_account WHERE student_id=?", id)
                                > 0;
        if (keep) {
            sql.update("UPDATE student SET enabled=FALSE WHERE id=?", id);
        } else {
            sql.update("DELETE FROM student WHERE id=?", id);
        }
        audit.record(
                actor,
                keep ? "STUDENT_DISABLE" : "STUDENT_DELETE",
                "STUDENT",
                id,
                before,
                keep ? students.find(id) : null,
                reason);
        return keep ? "DISABLED" : "DELETED";
    }

    /** 为学生创建登录账号，返回公开信息，不回传密码或密码哈希。 */
    @Transactional
    public AccountCreated createAccount(CurrentUser actor, long id, AccountInput input) {
        ActorService.requireAdmin(actor);
        lock(id);
        find(actor, id);
        long accountId =
                sql.insert(
                        """
                        INSERT INTO user_account(username,password_hash,role,enabled,student_id)
                        VALUES(?,?,'STUDENT',TRUE,?)
                        """,
                        input.username(),
                        encoder.encode(input.password()),
                        id);
        AccountCreated result = new AccountCreated(accountId, input.username(), "STUDENT", id);
        audit.record(actor, "ACCOUNT_CREATE", "ACCOUNT", accountId, null, result, "创建学生账号");
        return result;
    }

    private void lock(long id) {
        if (sql.one("SELECT id FROM student WHERE id=? FOR UPDATE", id) == null) {
            throw BusinessException.notFound();
        }
    }

    public static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw BusinessException.invalid("请填写1到500个字符的操作原因");
        }
    }
}
