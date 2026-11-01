package com.libramind.ai;

import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** 在独立短事务中预留模型调用预算，并记录实际 token 用量的估算费用。 */
@Service
public class AiBudgetService {

    private static final BigDecimal RESERVE = new BigDecimal("0.005000");
    private final SqlStore sql;
    private final BigDecimal dailyLimit;

    public AiBudgetService(
            SqlStore sql, @Value("${app.ai.daily-budget-usd:0.50}") BigDecimal dailyLimit) {
        this.sql = sql;
        this.dailyLimit = dailyLimit;
    }

    /** 校验固定低价白名单，并预留一次调用额度；不允许 auto 或昂贵回退。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserve(Long userId, String type, String model) {
        if (!model.equals(AiGateway.CHAT_MODEL) && !model.equals(AiGateway.EMBEDDING_MODEL)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "MODEL_NOT_ALLOWED", "只允许已核实价格的国产低价模型");
        }
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        sql.update(
                "INSERT INTO ai_budget_day(budget_date,reserved_usd) VALUES(?,0) ON DUPLICATE KEY"
                        + " UPDATE budget_date=budget_date",
                day);
        if (sql.update(
                        "UPDATE ai_budget_day SET reserved_usd=reserved_usd+? WHERE budget_date=?"
                                + " AND reserved_usd+?<=?",
                        RESERVE,
                        day,
                        RESERVE,
                        dailyLimit)
                != 1) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS, "AI_BUDGET_EXCEEDED", "今日模型测试预算已达到上限");
        }
        return sql.insert(
                "INSERT INTO ai_request(user_id,request_type,model,reserved_usd) VALUES(?,?,?,?)",
                userId,
                type,
                model,
                RESERVE);
    }

    /** 记录成功用量，按查验价格估算费用；估算值不是供应商最终账单。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(
            long id, String model, int inputTokens, int outputTokens, long milliseconds) {
        BigDecimal inputRate =
                new BigDecimal(model.equals(AiGateway.CHAT_MODEL) ? "0.0282" : "0.08");
        BigDecimal outputRate = new BigDecimal(model.equals(AiGateway.CHAT_MODEL) ? "0.282" : "0");
        BigDecimal cost =
                inputRate
                        .multiply(BigDecimal.valueOf(inputTokens))
                        .add(outputRate.multiply(BigDecimal.valueOf(outputTokens)))
                        .divide(new BigDecimal("1000000"), 6, RoundingMode.CEILING);
        var row =
                sql.one(
                        "SELECT created_at FROM ai_request WHERE id=? AND status='PENDING' FOR"
                                + " UPDATE",
                        id);
        if (row == null) {
            return;
        }
        LocalDate day = SqlStore.instant(row, "created_at").atOffset(ZoneOffset.UTC).toLocalDate();
        sql.update(
                "UPDATE ai_request SET"
                    + " status='SUCCEEDED',input_tokens=?,output_tokens=?,estimated_usd=?,latency_ms=?"
                    + " WHERE id=?",
                inputTokens,
                outputTokens,
                cost,
                milliseconds,
                id);
        sql.update(
                "UPDATE ai_budget_day SET reserved_usd=GREATEST(0,reserved_usd-?+?) WHERE"
                        + " budget_date=?",
                RESERVE,
                cost,
                day);
    }

    /** 请求失败时保守保留预留额度，不记录供应商原始错误或认证头。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(long id, long milliseconds) {
        sql.update(
                "UPDATE ai_request SET"
                    + " status='FAILED',error_code='PROVIDER_UNAVAILABLE',latency_ms=? WHERE id=?"
                    + " AND status='PENDING'",
                milliseconds,
                id);
    }
}
