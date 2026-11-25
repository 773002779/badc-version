package com.sf.release.infra.repo;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * The {@code user_role} join table, written by the approver settings block (P6-B design §2.2).
 *
 * <p>Role membership is the authority the approval chain reads on every request
 * ({@code JdbcAuthProvider}), so the settings save replaces the rows of exactly the two approval
 * roles instead of keeping a second approver table. Only these three statements are needed; the
 * table is otherwise read by {@code JdbcAuthProvider} with plain JDBC.
 */
@Mapper
public interface UserRoleMapper {

    /** The users currently holding {@code role}, in a stable order for the settings page. */
    @Select("SELECT user_id FROM user_role WHERE role = #{role} ORDER BY user_id")
    List<String> selectUsersByRole(@Param("role") String role);

    /** Removes every membership row of {@code role}; the caller re-inserts the saved list. */
    @Delete("DELETE FROM user_role WHERE role = #{role}")
    int deleteByRole(@Param("role") String role);

    /** Adds one membership row. */
    @Insert("INSERT INTO user_role (user_id, role) VALUES (#{userId}, #{role})")
    int insertMembership(@Param("userId") String userId, @Param("role") String role);
}