package library.model;

/**
 * 图书状态枚举。
 * 常量：AVAILABLE 可借，BORROWED 在借，OFF_SHELF 已下架。
 */
public enum BookStatus {
    /** 可借。 */
    AVAILABLE,

    /** 在借。 */
    BORROWED,

    /** 已下架。 */
    OFF_SHELF
}
