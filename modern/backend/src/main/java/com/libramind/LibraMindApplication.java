package com.libramind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 现代版后端入口，启动接口服务、数据库迁移和登录权限配置。 */
@SpringBootApplication
@EnableScheduling
public class LibraMindApplication {

    /** 启动 Spring Boot；args 是命令行参数。 */
    public static void main(String[] args) {
        SpringApplication.run(LibraMindApplication.class, args);
    }
}
