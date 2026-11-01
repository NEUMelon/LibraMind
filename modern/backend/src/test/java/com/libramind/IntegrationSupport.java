package com.libramind;

import static org.assertj.core.api.Assertions.assertThat;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.mapper.AccountMapper;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.catalog.CatalogService;
import com.libramind.common.db.SqlStore;
import com.libramind.student.StudentDtos.*;
import com.libramind.student.StudentService;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(
        properties = {
            "spring.config.import=file:./config/test-local.properties",
            "app.ai.index-worker-enabled=false"
        })
public abstract class IntegrationSupport {
    static final AtomicLong NUMBERS = new AtomicLong(System.currentTimeMillis());
    @Autowired protected StudentService students;
    @Autowired protected CatalogService catalog;
    @Autowired protected SqlStore sql;
    @Autowired protected AccountMapper accounts;
    protected CurrentUser admin;

    @BeforeEach
    void testDatabaseOnly() {
        assertThat(sql.one("SELECT DATABASE() AS name").get("name")).isEqualTo("libramind_test");
        var a = accounts.findByUsername("admin");
        admin = new CurrentUser(a.id(), a.username(), a.role(), a.studentId());
    }

    protected StudentView student() {
        return students.create(
                admin,
                new StudentInput(
                        Long.toString(NUMBERS.incrementAndGet()), "模拟测试学生", "信息学院", "自动化", true));
    }

    protected CurrentUser reader() {
        var student = student();
        var account =
                students.createAccount(
                        admin,
                        student.id(),
                        new AccountInput("it-" + UUID.randomUUID(), "TestOnly-2026-password"));
        return new CurrentUser(account.id(), account.username(), "STUDENT", student.id());
    }

    protected BookView book(String title) {
        return catalog.createBook(
                admin,
                new BookInput(
                        null, title, "", "测试作者", "", null, "测试出版社", "2026", "第1版", "", "zh",
                        "TP312", "计算机", "仅用于自动测试", ""));
    }

    protected CopyView copy() {
        var book = book("Java测试图书");
        return catalog.createCopy(
                admin,
                new CopyInput(
                        "IT-" + UUID.randomUUID(),
                        book.id(),
                        "TP312/IT",
                        "测试书架",
                        CopyStatus.AVAILABLE));
    }
}
