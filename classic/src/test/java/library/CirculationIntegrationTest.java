package library;

import library.dao.*;
import library.db.*;
import library.model.*;
import library.service.*;
import library.tools.SetupMain;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在独立 MySQL 测试库验证真实 SQL、外键、锁、事务回滚和历史一致性。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CirculationIntegrationTest {
    DatabaseManager db;
    StudentDAO students;
    BookDAO books;
    LoanDAO loans;
    CirculationService service;
    long sid, bid;
    final LocalDateTime start = LocalDateTime.now().minusDays(20).withSecond(0).withNano(0);

    /** 仅允许使用明确的测试库，防止测试清理演示数据。 */
    @BeforeAll
    void connect() throws Exception {
        Path config =
                Path.of(System.getProperty("library.test.config", "config/test-db.properties"));
        Assumptions.assumeTrue(
                Files.exists(config), "需要本地 config/test-db.properties，集成测试不可在演示库上运行。");
        Properties p = DatabaseManager.load(config);
        if (!p.getProperty("url").matches("jdbc:mysql://[^/]+/library_traditional_test(?:\\?.*)?"))
            throw new IllegalArgumentException("集成测试仅允许 library_traditional_test");
        db = new DatabaseManager(config);
        try (Connection c = db.getConnection()) {
            SetupMain.createTables(c);
        }
        students = new StudentDAO(db);
        books = new BookDAO(db);
        loans = new LoanDAO(db);
        service = new CirculationService(db);
    }

    /** 每条测试重新建立少量虚构数据，测试间互不依赖。 */
    @BeforeEach
    void fixture() throws Exception {
        try (Connection c = db.getConnection()) {
            Jdbc.update(c, "DELETE FROM loan");
            Jdbc.update(c, "DELETE FROM book");
            Jdbc.update(c, "DELETE FROM student");
            Jdbc.update(
                    c,
                    "INSERT INTO student(student_no,name,college,major) VALUES('0001','测试学生甲','信息学院','自动化'),('0002','测试学生乙','计算机学院','软件工程')");
            Jdbc.update(
                    c,
                    "INSERT INTO book(book_no,title,author,edition) VALUES('TEST-01','测试图书',?,'第1版'),('TEST-02','测试图书','作者乙','第1版')",
                    "作者 O'Neil");
        }
        sid = studentId("0001");
        bid = bookId("TEST-01");
    }

    /** 所有测试完成后关闭连接池。 */
    @AfterAll
    void close() {
        if (db != null) db.close();
    }

    /** 每条用例结束时核对持久化数量，覆盖正常、失败、重复和并发操作。 */
    @AfterEach
    void verifyBorrowedCounts() throws Exception {
        assertEquals(
                0,
                scalar(
                        "SELECT COUNT(*) FROM student s WHERE s.borrowed_count<>(SELECT COUNT(*) FROM loan l WHERE l.student_id=s.id AND l.status='ACTIVE')"));
    }

    long studentId(String no) throws Exception {
        try (Connection c = db.getConnection()) {
            return Jdbc.count(c, "SELECT id FROM student WHERE student_no=?", no);
        }
    }

    long bookId(String no) throws Exception {
        try (Connection c = db.getConnection()) {
            return Jdbc.count(c, "SELECT id FROM book WHERE book_no=?", no);
        }
    }

    long borrow() throws Exception {
        return service.borrow("0001", "TEST-01", start, "测试借阅");
    }

    long scalar(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection()) {
            return Jdbc.count(c, sql, args);
        }
    }

    @Test
    @DisplayName("学生特殊文本可保存，前导零学号保持文本")
    void studentCrud() throws Exception {
        StudentPO p = students.find(sid);
        p.setName("林<&\" O'Neil");
        p.setCollege("工学院");
        students.save(p);
        StudentPO got = students.find(sid);
        assertEquals(p.getName(), got.getName());
        assertEquals("0001", got.getStudentNo());
        assertEquals("工学院", got.getCollege());
    }

    @Test
    @DisplayName("重复学号被数据库唯一键拒绝")
    void duplicateStudent() throws Exception {
        StudentPO p = students.find(sid);
        p.setId(0);
        assertThrows(SQLException.class, () -> students.save(p));
        assertEquals(2, students.list("", "", 1, 10).getTotal());
    }

    @Test
    @DisplayName("无历史学生可以物理删除")
    void deleteUnusedStudent() throws Exception {
        students.delete(sid);
        assertNull(students.find(sid));
    }

    @Test
    @DisplayName("同书名不同书号可以共存")
    void sameTitleCopies() throws Exception {
        assertEquals(2, books.list("测试图书", null, 1, 10).getTotal());
    }

    @Test
    @DisplayName("重复书号被拒绝")
    void duplicateBook() throws Exception {
        BookPO p = books.find(bid);
        p.setId(0);
        assertThrows(SQLException.class, () -> books.save(p));
    }

    @Test
    @DisplayName("图书元数据可以修改且唯一书号保持稳定")
    void bookEdit() throws Exception {
        BookPO p = books.find(bid);
        p.setTitle("新版测试图书");
        p.setBookNo("IGNORED");
        books.save(p);
        assertEquals("新版测试图书", books.find(bid).getTitle());
        assertEquals("TEST-01", books.find(bid).getBookNo());
    }

    @Test
    @DisplayName("无历史图书可以删除")
    void deleteUnusedBook() throws Exception {
        books.delete(bid);
        assertNull(books.find(bid));
    }

    @Test
    @DisplayName("借阅原子创建记录、占用馆藏并派生数量")
    void borrowAtomic() throws Exception {
        long id = borrow();
        assertEquals(LoanStatus.ACTIVE, loans.find(id).getStatus());
        assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
        assertEquals(1, students.find(sid).getBorrowedCount());
    }

    @Test
    @DisplayName("同一册重复借阅失败且不增加第二条记录")
    void duplicateBorrow() throws Exception {
        borrow();
        assertThrows(BusinessException.class, () -> service.borrow("0002", "TEST-01", start, ""));
        assertEquals(1, scalar("SELECT COUNT(*) FROM loan"));
    }

    @Test
    @DisplayName("已停用学生不能新借书")
    void disabledStudent() throws Exception {
        StudentPO p = students.find(sid);
        p.setEnabled(false);
        students.save(p);
        assertThrows(BusinessException.class, this::borrow);
        assertEquals(BookStatus.AVAILABLE, books.find(bid).getStatus());
    }

    @Test
    @DisplayName("下架图书不能新借阅")
    void offShelf() throws Exception {
        BookPO p = books.find(bid);
        p.setStatus(BookStatus.OFF_SHELF);
        books.save(p);
        assertThrows(BusinessException.class, this::borrow);
    }

    @Test
    @DisplayName("不存在的对象被拒绝")
    void missingObjects() throws Exception {
        assertThrows(BusinessException.class, () -> service.borrow("999", "TEST-01", start, ""));
        assertThrows(BusinessException.class, () -> service.borrow("0001", "NONE", start, ""));
        assertEquals(0, scalar("SELECT COUNT(*) FROM loan"));
    }

    @Test
    @DisplayName("归还同时释放馆藏，重复归还不重复改变数量")
    void returnIdempotent() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(1), "");
        service.giveBack(id, start.plusDays(2), "");
        assertEquals(start.plusDays(1), loans.find(id).getReturnedAt());
        assertEquals(0, students.find(sid).getBorrowedCount());
        assertEquals(BookStatus.AVAILABLE, books.find(bid).getStatus());
    }

    @Test
    @DisplayName("归还早于借阅会回滚")
    void returnTooEarly() throws Exception {
        long id = borrow();
        assertThrows(
                BusinessException.class, () -> service.giveBack(id, start.minusMinutes(1), ""));
        assertEquals(LoanStatus.ACTIVE, loans.find(id).getStatus());
        assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
    }

    @Test
    @DisplayName("已有借阅的学生停用后仍可归还")
    void disabledCanReturn() throws Exception {
        long id = borrow();
        StudentPO p = students.find(sid);
        p.setEnabled(false);
        students.save(p);
        service.giveBack(id, start.plusDays(1), "");
        assertEquals(LoanStatus.RETURNED, loans.find(id).getStatus());
    }

    @Test
    @DisplayName("借阅和归还日期可合法更正且数量不漂移")
    void correctDates() throws Exception {
        long id = borrow();
        service.correct(id, start.minusDays(1), false, "登记时间更正");
        service.giveBack(id, start.plusDays(2), "");
        service.correct(id, start.plusDays(1), true, "归还时间更正");
        assertEquals(start.minusDays(1), loans.find(id).getBorrowedAt());
        assertEquals(start.plusDays(1), loans.find(id).getReturnedAt());
        assertEquals(0, students.find(sid).getBorrowedCount());
        assertTrue(loans.find(id).getNote().contains("原值"));
    }

    @Test
    @DisplayName("未来借阅日期被拒绝")
    void futureTime() throws Exception {
        assertThrows(
                BusinessException.class,
                () -> service.borrow("0001", "TEST-01", LocalDateTime.now().plusDays(1), ""));
    }

    @Test
    @DisplayName("更正和作废必须写明原因")
    void requireReason() throws Exception {
        long id = borrow();
        assertThrows(BusinessException.class, () -> service.cancel(id, " "));
        assertThrows(BusinessException.class, () -> service.correct(id, start, false, ""));
        assertEquals(LoanStatus.ACTIVE, loans.find(id).getStatus());
    }

    @Test
    @DisplayName("作废在借记录会释放图书和派生数量")
    void cancelActive() throws Exception {
        long id = borrow();
        service.cancel(id, "误登记");
        assertEquals(LoanStatus.CANCELLED, loans.find(id).getStatus());
        assertEquals(BookStatus.AVAILABLE, books.find(bid).getStatus());
        assertEquals(0, students.find(sid).getBorrowedCount());
    }

    @Test
    @DisplayName("作废旧已归还记录不影响后续在借记录")
    void cancelOldReturned() throws Exception {
        long old = borrow();
        service.giveBack(old, start.plusDays(1), "");
        long newer = service.borrow("0002", "TEST-01", start.plusDays(2), "");
        service.cancel(old, "旧历史误录");
        assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
        assertEquals(LoanStatus.ACTIVE, loans.find(newer).getStatus());
        assertEquals(1, students.find(studentId("0002")).getBorrowedCount());
    }

    @Test
    @DisplayName("最新归还可以撤销，并保留原归还时间")
    void undoLatest() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(1), "");
        service.undoReturn(id, "误点归还");
        assertNull(loans.find(id).getReturnedAt());
        assertEquals(LoanStatus.ACTIVE, loans.find(id).getStatus());
        assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
        assertTrue(loans.find(id).getNote().contains("原归还时间"));
        assertEquals(1, students.find(sid).getBorrowedCount());
    }

    @Test
    @DisplayName("存在后续借阅时不能撤销旧归还，即使后续也已归还")
    void undoOldReject() throws Exception {
        long old = borrow();
        service.giveBack(old, start.plusDays(1), "");
        long newer = service.borrow("0002", "TEST-01", start.plusDays(2), "");
        service.giveBack(newer, start.plusDays(3), "");
        assertThrows(BusinessException.class, () -> service.undoReturn(old, "误点"));
        assertEquals(BookStatus.AVAILABLE, books.find(bid).getStatus());
    }

    @Test
    @DisplayName("下架后的图书不能撤销归还")
    void undoOffShelfReject() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(1), "");
        BookPO p = books.find(bid);
        p.setStatus(BookStatus.OFF_SHELF);
        books.save(p);
        assertThrows(BusinessException.class, () -> service.undoReturn(id, "误点"));
    }

    @Test
    @DisplayName("新增借阅不能早于上一次归还")
    void avoidBackdatedOverlap() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(3), "");
        assertThrows(
                BusinessException.class,
                () -> service.borrow("0002", "TEST-01", start.plusDays(2), ""));
    }

    @Test
    @DisplayName("更正归还日期不能穿过下一次借阅")
    void correctOverlapReject() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(1), "");
        service.borrow("0002", "TEST-01", start.plusDays(2), "");
        assertThrows(
                BusinessException.class, () -> service.correct(id, start.plusDays(3), true, "更正"));
        assertEquals(start.plusDays(1), loans.find(id).getReturnedAt());
    }

    @Test
    @DisplayName("在借学生和图书均受删除保护")
    void deleteActiveReject() throws Exception {
        borrow();
        assertThrows(BusinessException.class, () -> students.delete(sid));
        assertThrows(BusinessException.class, () -> books.delete(bid));
        assertNotNull(students.find(sid));
        assertNotNull(books.find(bid));
    }

    @Test
    @DisplayName("有历史资料删除转停用或下架，关联仍可查询")
    void historicalDelete() throws Exception {
        long id = borrow();
        service.giveBack(id, start.plusDays(1), "");
        students.delete(sid);
        books.delete(bid);
        assertFalse(students.find(sid).isEnabled());
        assertEquals(BookStatus.OFF_SHELF, books.find(bid).getStatus());
        assertEquals("0001", loans.find(id).getStudentNo());
        assertEquals("TEST-01", loans.find(id).getBookNo());
    }

    @Test
    @DisplayName("图书普通编辑不能绕过归还改变在借状态")
    void editBorrowedReject() throws Exception {
        borrow();
        BookPO p = books.find(bid);
        p.setStatus(BookStatus.AVAILABLE);
        assertThrows(BusinessException.class, () -> books.save(p));
        assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
    }

    @Test
    @DisplayName("并发借同一册：两个独立事务最多一个成功")
    void concurrentBorrow() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> jobs = new ArrayList<>();
            for (String no : List.of("0001", "0002"))
                jobs.add(
                        executor.submit(
                                () -> {
                                    startGate.await();
                                    try {
                                        service.borrow(no, "TEST-01", start, "");
                                        return true;
                                    } catch (BusinessException e) {
                                        return false;
                                    }
                                }));
            startGate.countDown();
            int wins = 0;
            for (Future<Boolean> job : jobs) if (job.get(15, TimeUnit.SECONDS)) wins++;
            assertEquals(1, wins);
            assertEquals(1, scalar("SELECT COUNT(*) FROM loan WHERE status='ACTIVE'"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发归还同一记录保持幂等")
    void concurrentReturn() throws Exception {
        long id = borrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a =
                    executor.submit(
                            () -> {
                                service.giveBack(id, start.plusDays(1), "");
                                return null;
                            });
            Future<?> b =
                    executor.submit(
                            () -> {
                                service.giveBack(id, start.plusDays(1), "");
                                return null;
                            });
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals(LoanStatus.RETURNED, loans.find(id).getStatus());
            assertEquals(0, students.find(sid).getBorrowedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("数据库唯一约束即使绕过服务也阻止双重在借")
    void databaseConstraint() throws Exception {
        borrow();
        try (Connection c = db.getConnection()) {
            assertThrows(
                    SQLException.class,
                    () ->
                            Jdbc.update(
                                    c,
                                    "INSERT INTO loan(student_id,book_id,borrowed_at) VALUES(?,?,?)",
                                    studentId("0002"),
                                    bid,
                                    start));
        }
    }

    @Test
    @DisplayName("后半步 SQL 故障会撤销前半步 loan 插入")
    void injectedFailureRollback() throws Exception {
        try (Connection c = db.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "ALTER TABLE book ADD CONSTRAINT test_fail_book CHECK (status <> 'BORROWED')");
        }
        try {
            assertThrows(SQLException.class, this::borrow);
            assertEquals(0, scalar("SELECT COUNT(*) FROM loan"));
            assertEquals(BookStatus.AVAILABLE, books.find(bid).getStatus());
            assertEquals(0, scalar("SELECT borrowed_count FROM student WHERE id=?", sid));
        } finally {
            try (Connection c = db.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("ALTER TABLE book DROP CHECK test_fail_book");
            }
        }
    }

    @Test
    @DisplayName("断开再取连接仍能读取已提交借阅")
    void persistentAfterReconnect() throws Exception {
        long id = borrow();
        try (DatabaseManager second =
                new DatabaseManager(
                        Path.of(
                                System.getProperty(
                                        "library.test.config", "config/test-db.properties")))) {
            assertEquals(LoanStatus.ACTIVE, new LoanDAO(second).find(id).getStatus());
        }
    }

    @Test
    @DisplayName("参数化查询将 SQL 注入字符串当普通关键词")
    void sqlInjectionLiteral() throws Exception {
        assertEquals(0, books.list("' OR 1=1 --", null, 1, 10).getTotal());
        assertEquals(0, students.list("' OR 1=1 --", "", 1, 10).getTotal());
    }

    @Test
    @DisplayName("日期筛选包含结束日，分页夹取有效页码")
    void pageAndDate() throws Exception {
        borrow();
        assertEquals(
                1,
                loans.list(
                                "",
                                LoanStatus.ACTIVE,
                                start.toLocalDate(),
                                start.toLocalDate(),
                                false,
                                999,
                                10)
                        .getTotal());
        assertEquals(1, books.list("", null, 999, 8).getNumber());
        assertEquals(
                0,
                loans.list(
                                "",
                                LoanStatus.ACTIVE,
                                start.toLocalDate().plusDays(1),
                                null,
                                false,
                                1,
                                10)
                        .getTotal());
    }

    @Test
    @DisplayName("连接归还时回滚未提交写入，再借用恢复自动提交")
    void poolResetsTransaction() throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            Jdbc.update(
                    c,
                    "INSERT INTO student(student_no,name,college,major) VALUES('0099','不应持久化','测试','测试')");
        }
        try (Connection c = db.getConnection()) {
            assertTrue(c.getAutoCommit());
            assertEquals(0, Jdbc.count(c, "SELECT COUNT(*) FROM student WHERE student_no='0099'"));
        }
    }

    @Test
    @DisplayName("学生数量跳转使用精确学号，短学号不匹配其他人")
    void exactStudentFilter() throws Exception {
        borrow();
        assertEquals(
                0, loans.list("", LoanStatus.ACTIVE, null, null, false, 1, 10, "000").getTotal());
        assertEquals(
                1, loans.list("", LoanStatus.ACTIVE, null, null, false, 1, 10, "0001").getTotal());
    }

    @Test
    @DisplayName("登记表按完整学号和馆藏号精确核对，前缀不能误选")
    void exactLookupNumbers() throws Exception {
        assertEquals(sid, students.findByNumber("0001").getId());
        assertNull(students.findByNumber("000"));
        assertEquals(bid, books.findByNumber("TEST-01").getId());
        assertNull(books.findByNumber("TEST-0"));
        assertNull(books.findByNumber("' OR 1=1 --"));
    }

    @Test
    @DisplayName("选择列表排除停用和下架，精确核对仍能识别不可用原因")
    void lookupEligibility() throws Exception {
        try (Connection c = db.getConnection()) {
            Jdbc.update(c, "UPDATE student SET enabled=0 WHERE id=?", sid);
            Jdbc.update(c, "UPDATE book SET status='OFF_SHELF' WHERE id=?", bid);
        }
        assertFalse(students.findByNumber("0001").isEnabled());
        assertEquals(0, students.list("0001", "enabled", 1, 10).getTotal());
        assertEquals(BookStatus.OFF_SHELF, books.findByNumber("TEST-01").getStatus());
        assertEquals(0, books.list("TEST-01", BookStatus.AVAILABLE, 1, 10).getTotal());
    }

    @Test
    @DisplayName("数量保存在学生表，普通资料保存不能手工覆盖")
    void countIsStoredAndProtected() throws Exception {
        borrow();
        assertEquals(1, scalar("SELECT borrowed_count FROM student WHERE id=?", sid));
        StudentPO p = students.find(sid);
        p.setBorrowedCount(999);
        p.setName("更新姓名");
        students.save(p);
        assertEquals(1, students.find(sid).getBorrowedCount());
        StudentPO fresh = new StudentPO();
        fresh.setStudentNo("0003");
        fresh.setName("新学生");
        fresh.setCollege("理学院");
        fresh.setMajor("化学");
        fresh.setBorrowedCount(999);
        students.save(fresh);
        assertEquals(0, students.findByNumber("0003").getBorrowedCount());
    }

    @Test
    @DisplayName("数据库拒绝负的已借数量")
    void negativeStoredCountRejected() throws Exception {
        try (Connection c = db.getConnection()) {
            assertThrows(
                    SQLException.class,
                    () -> Jdbc.update(c, "UPDATE student SET borrowed_count=-1 WHERE id=?", sid));
        }
        assertEquals(0, students.find(sid).getBorrowedCount());
    }

    @Test
    @DisplayName("归还后半步失败时数量、记录和图书一起回滚")
    void returnCountRollback() throws Exception {
        long id = borrow();
        try (Connection c = db.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "ALTER TABLE book ADD CONSTRAINT test_fail_return_count CHECK (book_no <> 'TEST-01' OR status <> 'AVAILABLE')");
        }
        try {
            assertThrows(SQLException.class, () -> service.giveBack(id, start.plusDays(1), "测试回滚"));
            assertEquals(1, students.find(sid).getBorrowedCount());
            assertEquals(LoanStatus.ACTIVE, loans.find(id).getStatus());
            assertEquals(BookStatus.BORROWED, books.find(bid).getStatus());
        } finally {
            try (Connection c = db.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("ALTER TABLE book DROP CHECK test_fail_return_count");
            }
        }
    }

    @Test
    @DisplayName("同一学生并发借两册书不会丢失数量更新")
    void concurrentCountIncrements() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<?> a =
                    executor.submit(
                            () -> {
                                gate.await();
                                service.borrow("0001", "TEST-01", start, "");
                                return null;
                            });
            Future<?> b =
                    executor.submit(
                            () -> {
                                gate.await();
                                service.borrow("0001", "TEST-02", start, "");
                                return null;
                            });
            gate.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals(2, students.find(sid).getBorrowedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("封面路径保存到图书表并独立于馆藏号")
    void coverPathIsPersistent() throws Exception {
        BookPO p = books.find(bid);
        p.setCoverPath("shared-cover.jpg");
        books.save(p);
        try (Connection c = db.getConnection()) {
            Jdbc.update(c, "UPDATE book SET book_no='RENAMED-BOOK' WHERE id=?", bid);
        }
        assertEquals("shared-cover.jpg", books.find(bid).getCoverPath());
        BookPO copy = books.find(bid);
        copy.setId(0);
        copy.setBookNo("SECOND-COPY");
        books.save(copy);
        assertEquals("shared-cover.jpg", books.findByNumber("SECOND-COPY").getCoverPath());
    }

    @Test
    @DisplayName("非法封面路径不会写入数据库")
    void invalidCoverPathRejected() throws Exception {
        BookPO p = books.find(bid);
        p.setCoverPath("../secret.jpg");
        assertThrows(BusinessException.class, () -> books.save(p));
        assertNull(books.find(bid).getCoverPath());
    }
}
