package com.libramind.loan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 集中保存可配置的演示借阅规则，供流通服务、确认卡和规则资料共用。 */
@Component
public record CirculationRules(int maxActive, int loanDays, int maxRenewals, int renewalDays) {
    public CirculationRules(
            @Value("${app.circulation.max-active:5}") int maxActive,
            @Value("${app.circulation.loan-days:30}") int loanDays,
            @Value("${app.circulation.max-renewals:1}") int maxRenewals,
            @Value("${app.circulation.renewal-days:30}") int renewalDays) {
        if (maxActive < 1
                || maxActive > 100
                || loanDays < 1
                || loanDays > 365
                || maxRenewals < 0
                || maxRenewals > 10
                || renewalDays < 1
                || renewalDays > 365) {
            throw new IllegalArgumentException("Circulation rule configuration out of bounds");
        }
        this.maxActive = maxActive;
        this.loanDays = loanDays;
        this.maxRenewals = maxRenewals;
        this.renewalDays = renewalDays;
    }
}
