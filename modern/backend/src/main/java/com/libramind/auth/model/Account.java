package com.libramind.auth.model;

/** 数据库账号记录；passwordHash 仅供认证使用，不能直接作为接口响应返回。 */
public record Account(
        long id,
        String username,
        String passwordHash,
        String role,
        boolean enabled,
        Long studentId) {}
