package com.libramind.loan;

import static org.assertj.core.api.Assertions.*;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.mapper.AccountMapper;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.catalog.CatalogService;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;
import com.libramind.loan.LoanDtos.*;
import com.libramind.student.StudentDtos.*;
import com.libramind.student.StudentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** 在专用 MySQL 测试库验证事务、并发、历史维护及个人数据隔离。 */
@SpringBootTest(properties = {"spring.config.import=file:./config/test-local.properties"})
class CirculationIT {

    private static final AtomicLong NUMBERS = new AtomicLong(System.currentTimeMillis());
    @Autowired StudentService students;
    @Autowired CatalogService catalog;
    @Autowired CatalogDAO catalogDAO;
    @Autowired LoanService loans;
    @Autowired SqlStore sql;
    @Autowired AccountMapper accounts;
    @Autowired PlatformTransactionManager transactions;
    CurrentUser admin;

    @BeforeEach
    void dedicatedSchemaOnly() {
        assertThat(sql.one("SELECT DATABASE() AS name").get("name")).isEqualTo("libramind_test");
        var account = accounts.findByUsername("admin");
        admin =
                new CurrentUser(
                        account.id(), account.username(), account.role(), account.studentId());
    }

    @Test
    void borrowRenewReturnAndCancelPreserveCountsAndAudit() {
        StudentView student = student();
        CopyView copy = copy();
        LoanView first = borrow(student, copy);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isEqualTo(1);
        assertThat(catalog.copy(copy.id()).circulationStatus()).isEqualTo(CopyStatus.BORROWED);
        LoanView renewed = loans.renew(admin, first.id(), null);
        assertThat(renewed.dueAt()).isEqualTo(first.dueAt().plus(30, ChronoUnit.DAYS));
        assertThat(renewed.renewCount()).isEqualTo(1);
        assertThatThrownBy(() -> loans.renew(admin, first.id(), null))
                .isInstanceOf(BusinessException.class);
        LoanView returned =
                loans.giveBack(
                        admin,
                        new ReturnInput(first.id(), null, "已收回"),
                        "return-" + UUID.randomUUID());
        assertThat(returned.status()).isEqualTo(LoanStatus.RETURNED);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isZero();
        assertThatThrownBy(() -> loans.renew(admin, first.id(), null))
                .isInstanceOf(BusinessException.class);
        loans.cancel(admin, first.id(), "测试历史作废");
        assertThat(catalog.copy(copy.id()).circulationStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(
                        sql.count(
                                "SELECT COUNT(*) FROM audit_log WHERE target_type='LOAN' AND"
                                        + " target_id=?",
                                first.id()))
                .isEqualTo(4);
    }

    @Test
    void idempotentBorrowReturnsOriginalAndRejectsChangedPayload() {
        StudentView student = student();
        CopyView copy = copy();
        String key = UUID.randomUUID().toString();
        BorrowInput input = new BorrowInput(student.id(), List.of(copy.accessionNo()), "同一操作");
        BorrowResult first = loans.borrow(admin, input, key);
        BorrowResult second = loans.borrow(admin, input, key);
        assertThat(second).isEqualTo(first);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                loans.borrow(
                                        admin,
                                        new BorrowInput(student.id(), input.accessionNos(), "不同内容"),
                                        key))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不同操作或参数");
    }

    @Test
    void batchFailureDoesNotLeaveAnyPartialLoan() {
        StudentView firstStudent = student();
        StudentView secondStudent = student();
        CopyView free = copy();
        CopyView occupied = copy();
        borrow(firstStudent, occupied);
        long before = sql.count("SELECT COUNT(*) FROM loan WHERE student_id=?", secondStudent.id());
        assertThatThrownBy(
                        () ->
                                loans.borrow(
                                        admin,
                                        new BorrowInput(
                                                secondStudent.id(),
                                                List.of(free.accessionNo(), occupied.accessionNo()),
                                                "整批测试"),
                                        UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(sql.count("SELECT COUNT(*) FROM loan WHERE student_id=?", secondStudent.id()))
                .isEqualTo(before);
        assertThat(catalog.copy(free.id()).circulationStatus()).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void concurrentSameCopyHasAtMostOneSuccessfulBorrower() throws Exception {
        StudentView a = student();
        StudentView b = student();
        CopyView copy = copy();
        assertThat(race(() -> borrow(a, copy), () -> borrow(b, copy))).isEqualTo(1);
        assertThat(
                        sql.count(
                                "SELECT COUNT(*) FROM loan WHERE copy_id=? AND status='ACTIVE'",
                                copy.id()))
                .isEqualTo(1);
    }

    @Test
    void concurrentStudentQuotaCannotExceedFive() throws Exception {
        StudentView student = student();
        for (int i = 0; i < 4; i++) {
            borrow(student, copy());
        }
        CopyView a = copy();
        CopyView b = copy();
        assertThat(race(() -> borrow(student, a), () -> borrow(student, b))).isEqualTo(1);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isEqualTo(5);
    }

    @Test
    void laterBorrowPreventsUndoAndOldCancellationDoesNotReleaseCurrentLoan() {
        StudentView student = student();
        CopyView copy = copy();
        LoanView first = borrow(student, copy);
        loans.giveBack(admin, new ReturnInput(first.id(), null, null), null);
        LoanView next = borrow(student, copy);
        assertThatThrownBy(() -> loans.undoReturn(admin, first.id(), "错误撤销"))
                .isInstanceOf(BusinessException.class);
        loans.cancel(admin, first.id(), "只作废旧历史");
        assertThat(loans.find(admin, next.id()).status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(catalog.copy(copy.id()).circulationStatus()).isEqualTo(CopyStatus.BORROWED);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isEqualTo(1);
    }

    @Test
    void disabledStudentCanReturnButCannotBorrow() {
        StudentView student = student();
        CopyView copy = copy();
        LoanView loan = borrow(student, copy);
        students.update(
                admin,
                student.id(),
                new StudentInput(
                        student.studentNo(),
                        student.name(),
                        student.college(),
                        student.major(),
                        false));
        loans.giveBack(admin, new ReturnInput(loan.id(), null, null), null);
        assertThatThrownBy(() -> borrow(student, copy)).isInstanceOf(BusinessException.class);
    }

    @Test
    void studentsCannotReadOrModifyOthersLoans() {
        StudentView owner = student();
        LoanView loan = borrow(owner, copy());
        StudentView other = student();
        CurrentUser outsider = new CurrentUser(admin.id(), "test-student", "STUDENT", other.id());
        assertThatThrownBy(() -> loans.find(outsider, loan.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("记录不存在");
        assertThatThrownBy(() -> loans.renew(outsider, loan.id(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                loans.giveBack(
                                        outsider, new ReturnInput(loan.id(), null, null), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void historicalDeleteRetainsStudentAndCopyAndUnusedRowsAreDeleted() {
        StudentView student = student();
        CopyView copy = copy();
        LoanView loan = borrow(student, copy);
        assertThatThrownBy(() -> students.delete(admin, student.id(), "测试删除"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.deleteCopy(admin, copy.id(), "测试删除"))
                .isInstanceOf(BusinessException.class);
        loans.giveBack(admin, new ReturnInput(loan.id(), null, null), null);
        assertThat(students.delete(admin, student.id(), "保留历史")).isEqualTo("DISABLED");
        assertThat(catalog.deleteCopy(admin, copy.id(), "保留历史")).isEqualTo("OFF_SHELF");
        StudentView unusedStudent = student();
        CopyView unusedCopy = copy();
        assertThat(students.delete(admin, unusedStudent.id(), "无关联")).isEqualTo("DELETED");
        assertThat(catalog.deleteCopy(admin, unusedCopy.id(), "无关联")).isEqualTo("DELETED");
    }

    @Test
    void thrownFailureRollsBackLoanStatusAndSuccessfulAuditTogether() {
        StudentView student = student();
        CopyView copy = copy();
        long auditBefore = sql.count("SELECT COUNT(*) FROM audit_log");
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                status -> {
                                                    borrow(student, copy);
                                                    throw new IllegalStateException(
                                                            "injected after business write");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isZero();
        assertThat(catalog.copy(copy.id()).circulationStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log")).isEqualTo(auditBefore);
    }

    @Test
    void invalidReturnDatesAreRejectedAndValidCorrectionDoesNotChangeCount() {
        StudentView student = student();
        CopyView copy = copy();
        LoanView loan = borrow(student, copy);
        LoanView returned = loans.giveBack(admin, new ReturnInput(loan.id(), null, null), null);
        assertThatThrownBy(
                        () ->
                                loans.correct(
                                        admin,
                                        loan.id(),
                                        new CorrectionInput(
                                                null,
                                                null,
                                                loan.borrowedAt().minusSeconds(1),
                                                null,
                                                "非法日期"),
                                        true))
                .isInstanceOf(BusinessException.class);
        loans.correct(
                admin,
                loan.id(),
                new CorrectionInput(null, null, returned.returnedAt(), "已核对", "更正备注"),
                true);
        assertThat(students.find(admin, student.id()).currentBorrowedCount()).isZero();
        assertThat(catalog.copy(copy.id()).circulationStatus()).isEqualTo(CopyStatus.AVAILABLE);
    }

    private StudentView student() {
        return students.create(
                admin,
                new StudentInput(
                        Long.toString(NUMBERS.incrementAndGet()),
                        "测试学生",
                        "信息科学与工程学院",
                        "自动化",
                        true));
    }

    private CopyView copy() {
        BookView book =
                catalog.createBook(
                        admin,
                        new BookInput(
                                null,
                                "Java 测试图书",
                                "",
                                "测试作者",
                                "",
                                null,
                                "测试出版社",
                                "2026",
                                "第1版",
                                "",
                                "zh",
                                "TP312",
                                "计算机",
                                "仅用于测试",
                                ""));
        return catalog.createCopy(
                admin,
                new CopyInput(
                        "IT-" + UUID.randomUUID(),
                        book.id(),
                        "TP312/IT",
                        "测试书架",
                        CopyStatus.AVAILABLE));
    }

    private LoanView borrow(StudentView student, CopyView copy) {
        return loans.borrow(
                        admin,
                        new BorrowInput(student.id(), List.of(copy.accessionNo()), "测试"),
                        null)
                .loans()
                .getFirst();
    }

    private int race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(start, first));
            var b = executor.submit(() -> attempt(start, second));
            start.countDown();
            return a.get() + b.get();
        }
    }

    private int attempt(CountDownLatch start, Callable<?> action) throws Exception {
        start.await();
        try {
            action.call();
            return 1;
        } catch (BusinessException exception) {
            return 0;
        }
    }
}
