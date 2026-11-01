package com.libramind.auth.mapper;

import com.libramind.auth.model.Account;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号数据访问接口，按用户名读取账号，并用于首次创建管理员。 */
@Mapper
public interface AccountMapper {

    /** 按用户名查询账号，不存在时返回 null。 */
    @Select(
            """
            SELECT id, username, password_hash, role, enabled, student_id
            FROM user_account WHERE username = #{username}
            """)
    Account findByUsername(String username);

    /** 查询已有管理员数量，用于阻止重复执行首次初始化。 */
    @Select("SELECT COUNT(*) FROM user_account WHERE role = 'ADMIN'")
    long countAdministrators();

    /** 保存首次管理员账号，passwordHash 必须是编码后的密码，返回新增行数。 */
    @Insert(
            """
            INSERT INTO user_account (username, password_hash, role, enabled)
            VALUES (#{username}, #{passwordHash}, 'ADMIN', TRUE)
            """)
    int insertAdministrator(
            @Param("username") String username, @Param("passwordHash") String passwordHash);
}
