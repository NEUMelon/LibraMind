package com.libramind.common.db;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 封装 MyBatis 参数绑定和简单查询，供各模块 DAO 使用；不管理业务事务。 */
@Repository
public class SqlStore {

    private final SqlMapper mapper;

    public SqlStore(SqlMapper mapper) {
        this.mapper = mapper;
    }

    /** 执行带问号占位符的内部查询，参数交给 MyBatis 绑定。 */
    public List<Map<String, Object>> list(String sql, Object... values) {
        return mapper.query(bindSql(sql, values.length), parameters(values));
    }

    /** 查询单条记录；没有匹配记录时返回 null。 */
    public Map<String, Object> one(String sql, Object... values) {
        List<Map<String, Object>> rows = list(sql, values);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 查询第一行第一列的数值，用于总条数与存在性检查。 */
    public long count(String sql, Object... values) {
        Map<String, Object> row = one(sql, values);
        return row == null ? 0 : ((Number) row.values().iterator().next()).longValue();
    }

    /** 执行更新或删除，返回受影响行数。 */
    public int update(String sql, Object... values) {
        return mapper.update(bindSql(sql, values.length), parameters(values));
    }

    /** 执行新增，返回数据库生成的主键。 */
    public long insert(String sql, Object... values) {
        Map<String, Object> params = parameters(values);
        mapper.insert(bindSql(sql, values.length), params);
        return ((Number) params.get("id")).longValue();
    }

    /** 从查询结果读取字符串，SQL NULL 转为空串。 */
    public static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    public static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    public static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean flag
                ? flag
                : value instanceof Number n && n.intValue() != 0;
    }

    /** 将 JDBC 返回的时间统一转换为 UTC Instant。 */
    public static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime time) {
            return time.toInstant(ZoneOffset.UTC);
        }
        return (Instant) value;
    }

    /** 把内部占位符替换为 MyBatis 参数名；数量不匹配说明 DAO 编写有误。 */
    private String bindSql(String sql, int count) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        for (char character : sql.toCharArray()) {
            if (character == '?') {
                result.append("#{params.p").append(index++).append('}');
            } else {
                result.append(character);
            }
        }
        if (index != count) {
            throw new IllegalArgumentException("SQL parameter count mismatch");
        }
        return result.toString();
    }

    private Map<String, Object> parameters(Object[] values) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            params.put(
                    "p" + i, values[i] instanceof Instant time ? Timestamp.from(time) : values[i]);
        }
        return params;
    }
}
