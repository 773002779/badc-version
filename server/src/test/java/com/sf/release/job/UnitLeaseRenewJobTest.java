package com.sf.release.job;

import com.sf.release.infra.job.UnitLeaseRenewJob;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 60s 部署单元 lease renewer (review M2) against the real schema: a pipeline paused at
 * {@code PROD_DEPLOY} keeps its four-hour lease instead of losing the unit while it waits for an
 * approval, a freeze or the next batch window.
 *
 * <p>The job is invoked explicitly (the test profile disables its scheduler), exactly like the other
 * job tests. The lease row is the authority: when the in-memory/Redis key is gone after a restart,
 * renewing the row still keeps the unit reserved (another publisher is blocked by the live row), so
 * the test asserts the row's {@code expire_at} rather than the key.
 */
@SpringBootTest
@ActiveProfiles("test")
class UnitLeaseRenewJobTest {

    private static final String USER = "lease-user";

    @Autowired DeployUnitLockMapper lockMapper;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;

    private UnitLeaseRenewJob job;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        InMemoryDistributedLock.clearAllLeases();
        job = new UnitLeaseRenewJob(lockMapper, unitLockService);
    }

    @Test
    void renews_the_lease_of_a_running_and_of_a_paused_owner() {
        long runningUnit = givenUnit("lease-running");
        long running = givenInstance(runningUnit, "PL-LEASE-RUN", "RUNNING");
        givenLockRow(runningUnit, running, Instant.now().plusSeconds(60));

        long pausedUnit = givenUnit("lease-paused");
        long paused = givenInstance(pausedUnit, "PL-LEASE-PAUSE", "PAUSED");
        givenLockRow(pausedUnit, paused, Instant.now().plusSeconds(60));

        job.renewLeases();

        assertThat(expireAt(runningUnit))
            .as("a running pipeline keeps its unit")
            .isAfter(Instant.now().plusSeconds(3 * 3600));
        assertThat(expireAt(pausedUnit))
            .as("a long approval/freeze pause keeps its unit too")
            .isAfter(Instant.now().plusSeconds(3 * 3600));
    }

    @Test
    void renews_the_row_even_when_the_distributed_key_was_lost_in_a_restart() {
        long unit = givenUnit("lease-restart");
        long instance = givenInstance(unit, "PL-LEASE-RESTART", "PAUSED");
        givenLockRow(unit, instance, Instant.now().plusSeconds(60));
        // The restart cleared the in-memory lease; the DB row is the only remaining evidence.
        InMemoryDistributedLock.clearAllLeases();

        job.renewLeases();

        assertThat(expireAt(unit))
            .as("the live row still reserves the unit, so it must not be allowed to expire")
            .isAfter(Instant.now().plusSeconds(3 * 3600));
    }

    @Test
    void never_renews_a_terminal_owner() {
        long unit = givenUnit("lease-done");
        long instance = givenInstance(unit, "PL-LEASE-DONE", "DONE");
        // DATETIME(3) keeps milliseconds only, so the expectation is truncated the same way.
        Instant near = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        givenLockRow(unit, instance, near);

        job.renewLeases();

        assertThat(expireAt(unit))
            .as("a terminal pipeline releases its unit; renewing would reserve it forever")
            .isEqualTo(near);
    }

    @Test
    void a_lock_row_without_a_live_instance_is_ignored() {
        long unit = givenUnit("lease-orphan");
        givenLockRow(unit, 9_999_999L,
            Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS));
        Instant near = expireAt(unit);

        job.renewLeases();

        assertThat(expireAt(unit)).isEqualTo(near);
    }

    // ------------------------------------------------------------------ fixtures

    private long givenUnit(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, USER);
        Long subsystem = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class,
            name);
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
            + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, USER);
        Long unit = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class,
            name);
        return unit == null ? 0L : unit;
    }

    private long givenInstance(long unitId, String pipelineNo, String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
            + " window_result, deadline_at, status, row_version, created_at)"
            + " VALUES (?, 1, NOW(3), 'OUT', NOW(3), 'READY', 0, NOW(3))", pipelineNo);
        Long order = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, pipelineNo);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, start_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', ?, NOW(3), NOW(3))",
            order, pipelineNo, status);
        Long instance = jdbc.queryForObject("SELECT id FROM pipeline_instance WHERE pipeline_no = ?",
            Long.class, pipelineNo);
        assertThat(instance).isNotNull();
        return instance;
    }

    private void givenLockRow(long unitId, long instanceId, Instant expireAt) {
        jdbc.update("INSERT INTO deploy_unit_lock (unit_id, instance_id, acquired_at, expire_at)"
            + " VALUES (?, ?, NOW(3), ?)", unitId, instanceId, Timestamp.from(expireAt));
    }

    private Instant expireAt(long unitId) {
        Timestamp value = jdbc.queryForObject(
            "SELECT expire_at FROM deploy_unit_lock WHERE unit_id = ?", Timestamp.class, unitId);
        return value == null ? null : value.toInstant();
    }
}