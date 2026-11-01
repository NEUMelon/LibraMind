package com.libramind.auth.service;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.common.api.BusinessException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 从已认证会话读取当前操作者，管理操作统一在服务层检查角色。 */
@Service
public class ActorService {

    private final AuthService auth;

    public ActorService(AuthService auth) {
        this.auth = auth;
    }

    /** 返回当前账号的实时信息，未登录或账号停用时拒绝。 */
    public CurrentUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return auth.currentUser(authentication);
    }

    /** 要求管理员角色，成功时返回操作者。 */
    public CurrentUser admin() {
        CurrentUser actor = current();
        requireAdmin(actor);
        return actor;
    }

    public static void requireAdmin(CurrentUser actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "需要管理员权限");
        }
    }

    /** 学生只能访问本人记录，管理员可以访问全部。 */
    public static void requireOwner(CurrentUser actor, long studentId) {
        if (!"ADMIN".equals(actor.role())
                && (actor.studentId() == null || actor.studentId() != studentId)) {
            throw BusinessException.notFound();
        }
    }
}
