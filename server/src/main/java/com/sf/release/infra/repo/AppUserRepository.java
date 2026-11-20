package com.sf.release.infra.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only directory of local users. A subsystem owner or tool authorisation agent must
 * be an existing {@code ACTIVE} {@code app_user}; the application layer only needs this
 * existence check today, so no MyBatis mapper or PO is warranted.
 */
@Repository
public class AppUserRepository {

    private static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsActive(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id = ? AND status = ?",
            Integer.class, userId, ACTIVE);
        return count != null && count > 0;
    }
}