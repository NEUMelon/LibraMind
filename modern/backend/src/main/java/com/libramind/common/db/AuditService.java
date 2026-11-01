package com.libramind.common.db;

import com.libramind.auth.dto.AuthDtos.CurrentUser;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/** 在当前业务事务中保存操作前后快照，不记录密码、密钥或认证输入。 */
@Service
public class AuditService {

    private final SqlStore sql;
    private final ObjectMapper json;

    public AuditService(SqlStore sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    /** 保存一条成功操作记录；调用方必须在业务事务内调用。 */
    public void record(
            CurrentUser actor,
            String operation,
            String type,
            long id,
            Object before,
            Object after,
            String reason) {
        String requestId = MDC.get("requestId");
        sql.insert(
                """
                INSERT INTO audit_log(operator_id,operation_type,target_type,target_id,
                    before_snapshot,after_snapshot,reason,request_id)
                VALUES(?,?,?,?,?,?,?,?)
                """,
                actor.id(),
                operation,
                type,
                id,
                before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after),
                reason == null ? "" : reason,
                requestId == null ? "background" : requestId);
    }
}
