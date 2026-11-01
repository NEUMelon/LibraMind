package library.model;

/**
 * 借阅状态枚举。
 * 常量：ACTIVE 在借，RETURNED 已归还，CANCELLED 已作废。
 */
public enum LoanStatus {
    /** 在借，尚未归还。 */
    ACTIVE,

    /** 已归还。 */
    RETURNED,

    /** 误登记已作废，保留历史记录。 */
    CANCELLED
}
