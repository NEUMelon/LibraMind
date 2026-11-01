package com.libramind.auth.service;

import com.libramind.auth.mapper.AccountMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动时创建管理员；已有管理员时不修改账号或重置密码。 */
@Component
@Order(-100)
public class BootstrapAdministrator implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdministrator.class);
    private final AccountMapper accounts;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    /** 注入账号接口、密码编码器，以及私有配置中的首次管理员凭据。 */
    public BootstrapAdministrator(
            AccountMapper accounts,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.username}") String username,
            @Value("${app.bootstrap.password}") String password) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    /** 没有管理员且配置了初始化密码时创建账号；密码不会写入日志。 */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.countAdministrators() > 0) {
            return;
        }
        if (password.isBlank()) {
            LOG.warn("No administrator exists; configure app.bootstrap.password locally.");
            return;
        }
        if (password.length() < 12 || password.length() > 64) {
            throw new IllegalStateException("Bootstrap password must contain 12 to 64 characters");
        }
        accounts.insertAdministrator(username, passwordEncoder.encode(password));
        LOG.info("Initial administrator created. Credentials remain in local configuration.");
    }
}
