package com.libramind.auth.service;

import com.libramind.auth.mapper.AccountMapper;
import com.libramind.auth.model.Account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 从数据库加载账号和角色，供 Spring Security 校验密码与登录资格。 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AccountMapper accounts;

    /** 注入账号查询接口。 */
    public DatabaseUserDetailsService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    /** 按用户名返回认证所需的信息；账号不存在时抛出认证异常。 */
    @Override
    public UserDetails loadUserByUsername(String username) {
        Account account = accounts.findByUsername(username);
        if (account == null) {
            throw new UsernameNotFoundException("Account not found");
        }
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .roles(account.role())
                .disabled(!account.enabled())
                .build();
    }
}
