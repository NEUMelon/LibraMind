package library.model;

import java.util.List;

/**
 * 分页结果类，保存一次分页查询的结果。
 * 属性：items 当前页数据、total 总条数、number 当前页码、size 每页条数。
 * 方法：读取当前页数据、总条数、当前页码和总页数。
 *
 * @param <T> 保存的数据类型
 */
public class Page<T> {
    private final List<T> items;
    private final long total;
    private final int number, size;

    /**
     * 保存当前页数据和分页信息。
     *
     * @param items 当前页数据
     * @param total 符合查询条件的总记录数
     * @param number 当前页码
     * @param size 每页记录数
     */
    public Page(List<T> items, long total, int number, int size) {
        this.items = items;
        this.total = total;
        this.number = number;
        this.size = size;
    }

    /**
     * 获取当前页数据。
     *
     * @return 当前页数据
     */
    public List<T> getItems() {
        return items;
    }

    /**
     * 获取符合查询条件的总记录数。
     *
     * @return 符合查询条件的总记录数
     */
    public long getTotal() {
        return total;
    }

    /**
     * 获取当前页码。
     *
     * @return 当前页码
     */
    public int getNumber() {
        return number;
    }

    /**
     * 根据总条数和每页条数计算总页数。
     *
     * @return 总页数；没有记录时仍返回 1
     */
    public int getPages() {
        return (int) Math.max(1, (total + size - 1) / size);
    }
}
