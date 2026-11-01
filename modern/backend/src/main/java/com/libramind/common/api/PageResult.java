package com.libramind.common.api;

import java.util.List;

/** 分页查询结果，page 从 1 开始，空列表也保留第 1 页。 */
public record PageResult<T>(
        List<T> items, int page, int size, long totalElements, long totalPages) {

    /** 根据当前页和总条数计算总页数；输入 size 必须大于零。 */
    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(items, page, size, total, Math.max(1, (total + size - 1) / size));
    }

    /** 校验页码和每页条数，限制单次查询的数据量。 */
    public static void validate(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1L) * size > Integer.MAX_VALUE) {
            throw BusinessException.invalid("page 必须大于0，size 必须在1到100之间");
        }
    }
}
