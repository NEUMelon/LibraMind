package com.libramind.common.db;

import com.libramind.auth.dto.AuthDtos.CurrentUser;
import com.libramind.common.api.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/** 在业务事务内锁定请求标识，保存并重放成功结果，失败时随业务整体回滚。 */
@Service
public class IdempotencyService {

    private final SqlStore sql;
    private final ObjectMapper json;

    public IdempotencyService(SqlStore sql, ObjectMapper json) {
        this.sql = sql;
        this.json = json;
    }

    /** 同一账号和 key 只能对应同一操作与输入；调用方必须已经开启事务。 */
    public <T> T execute(
            CurrentUser actor,
            String key,
            String operation,
            Object input,
            Class<T> resultType,
            Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotency requires an active business transaction");
        }
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (!key.matches("[A-Za-z0-9._-]{1,64}")) {
            throw BusinessException.invalid("Idempotency-Key 只能包含1到64位字母、数字、点、横线或下划线");
        }
        String hash = digest(operation + "\n" + json.writeValueAsString(input));
        sql.update(
                """
                INSERT INTO idempotency_record(user_id,idem_key,request_hash) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """,
                actor.id(),
                key,
                hash);
        Map<String, Object> record =
                sql.one(
                        """
                        SELECT * FROM idempotency_record WHERE user_id=? AND idem_key=? FOR UPDATE
                        """,
                        actor.id(),
                        key);
        if (!hash.equals(SqlStore.text(record, "request_hash"))) {
            throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED", "同一个请求标识不能用于不同操作或参数");
        }
        String saved = SqlStore.text(record, "response_json");
        if (!saved.isBlank()) {
            return json.readValue(saved, resultType);
        }
        T result = action.get();
        sql.update(
                "UPDATE idempotency_record SET response_json=? WHERE id=?",
                json.writeValueAsString(result),
                record.get("id"));
        return result;
    }

    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
