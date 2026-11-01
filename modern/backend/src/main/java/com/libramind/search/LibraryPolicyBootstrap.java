package com.libramind.search;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.auth.mapper.AccountMapper;
import com.libramind.common.db.SqlStore;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 首次创建项目自有的演示借阅规则语料，不冒充学校正式制度。 */
@Component
@Order(100)
public class LibraryPolicyBootstrap implements ApplicationRunner {

    private final SqlStore sql;
    private final AccountMapper accounts;
    private final KnowledgeService knowledge;
    private final com.libramind.loan.CirculationRules rules;

    public LibraryPolicyBootstrap(
            SqlStore sql,
            AccountMapper accounts,
            KnowledgeService knowledge,
            com.libramind.loan.CirculationRules rules) {
        this.sql = sql;
        this.accounts = accounts;
        this.knowledge = knowledge;
        this.rules = rules;
    }

    @Override
    public void run(ApplicationArguments args) {
        var firstAdmin =
                sql.one(
                        "SELECT username FROM user_account WHERE role='ADMIN' AND enabled=TRUE"
                                + " ORDER BY id LIMIT 1");
        if (firstAdmin == null) {
            return;
        }
        var account = accounts.findByUsername(SqlStore.text(firstAdmin, "username"));
        if (account == null || !account.role().equals("ADMIN")) {
            return;
        }
        CurrentUser admin = new CurrentUser(account.id(), account.username(), account.role(), null);
        var input =
                new KnowledgeService.KnowledgeInput(
                        null,
                        "LibraMind 默认演示借阅规则",
                        """
                        本资料是课程项目的默认演示规则，不代表东北大学图书馆的正式制度。
                        每位启用学生最多同时借阅%d册，每次借期%d天。停用学生不能新增借阅，但仍可归还本人图书。
                        每条ACTIVE借阅最多续借%d次，从原应还日期延长%d天；已逾期、已归还、已作废的借阅不能续借。
                        逾期指仍在借且应还日期早于当前时间。本项目不收取罚金。
                        学生自助借阅是业务登记。扫描或手动输入馆藏登录号确定具体副本，不验证实物是否在场。
                        ISBN用于识别出版版本，不代表馆藏中的某一册。查看位置和索书号后，由用户明确提供副本登录号。
                        误借、日期更正和撤销误归还由管理员处理，必须填写原因并保留审计。
                        AI可以查书、查库存、查本人借阅并准备操作。所有AI写操作都要用户确认，模型不能自行确认。
                        """
                                .formatted(
                                        rules.maxActive(),
                                        rules.loanDays(),
                                        rules.maxRenewals(),
                                        rules.renewalDays()),
                        "LIBRARY_POLICY",
                        "项目自行编写的演示规则",
                        "",
                        "PUBLIC");
        var existing =
                sql.one(
                        "SELECT id,content FROM knowledge_document WHERE title=? AND source=? AND"
                                + " enabled=TRUE ORDER BY id LIMIT 1",
                        input.title(),
                        input.source());
        if (existing == null) {
            knowledge.create(admin, input);
        } else if (!SqlStore.text(existing, "content").equals(input.content())) {
            knowledge.update(admin, SqlStore.number(existing, "id"), input);
        }
    }
}
