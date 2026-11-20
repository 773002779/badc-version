package com.sf.release;

import com.sf.release.infra.repo.SubsystemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Task 1 test wiring end to end: the test profile points at the local
 * {@code release_pipeline_test} schema, Flyway cleans and migrates it, and MyBatis-Plus
 * mappers are registered and can query the {@code subsystem} table.
 */
@SpringBootTest
@ActiveProfiles("test")
class TestProfileSmokeTest {

    @Autowired SubsystemMapper subsystemMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void context_boots_and_subsystem_table_is_queryable() {
        assertThat(subsystemMapper.selectCount(null)).isNotNull().isGreaterThanOrEqualTo(0L);

        Integer migratedColumns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'subsystem'"
                + " AND column_name IN ('description', 'git_url', 'coding_project')",
            Integer.class);
        assertThat(migratedColumns)
            .as("V3__subsystem_extra.sql is applied and adds the subsystem detail columns")
            .isEqualTo(3);
    }
}