package com.libramind.common.db;

import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;
import java.util.Map;

/** MyBatis 的底层执行接口；SQL 仅由服务端 DAO 提供，参数统一绑定。 */
@Mapper
public interface SqlMapper {

    @SelectProvider(type = Provider.class, method = "statement")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> query(
            @Param("sql") String sql, @Param("params") Map<String, Object> params);

    @UpdateProvider(type = Provider.class, method = "statement")
    int update(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    @InsertProvider(type = Provider.class, method = "statement")
    @Options(useGeneratedKeys = true, keyProperty = "params.id")
    int insert(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /** 返回 DAO 内部定义的 SQL；任何接口都不得接收或转发任意 SQL 字符串。 */
    class Provider {
        public static String statement(Map<String, Object> arguments) {
            return (String) arguments.get("sql");
        }
    }
}
