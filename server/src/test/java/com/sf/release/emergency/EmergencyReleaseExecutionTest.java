package com.sf.release.emergency;

import com.sf.release.application.EmergencyReleaseService;
import com.sf.release.application.EmergencyReleaseService.EmergencyResult;
import com.sf.release.application.ExecuteProdService;
import com.sf.release.application.ExecuteProdService.ExecuteResult;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.ProductionBoundaryProbe;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The execution half of P6-D (design §1 "执行时复核", §5 item 4) against the real pipeline and the
 * real pre-execution review / batch-boundary probe:
 *
 * <ul>
 *   <li>an emergency-released order executes while the same freeze still blocks an ordinary one;
 *       the {@code EXECUTE_PROD} audit row is written as before;</li>
 *   <li>the batch-boundary probe skips the freeze for the marked order but keeps the gate check:
 *       a non-waivable block still pauses it;</li>
 *   <li>the image/digest and expired-waiver checks of the pre-execution review are not bypassed
 *       (40007 / 40002).</li>
 * </ul>
 *
 * <p>The instance is written by hand (the state right before the first production batch) and the
 * real {@code ProdDeployNodeHandler} is exercised with a stub engine, so the assertions can name the
 * batch that was triggered. Package {@code com.sf.release.emergency}, not {@code ..web..}.
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    EmergencyReleaseExecutionTest.StubEngineConfig.class})
@ActiveProfiles("test")
class EmergencyReleaseExecutionTest {

    @Autowired EmergencyReleaseService emergencyRelease;
    @Autowired ExecuteProdService executeProd;
    @Autowired ProductionBoundaryProbe boundary;
    @Autowired UnitLockService unitLockService;
    @Autowired StubEngine engine;
    @Autowired JdbcTemplate jdbc;

    private EmergencyFixtures fixtures;
    private EmergencyFixtures.Base base;

    @BeforeEach
    void setUp() {
        InMemoryDistributedLock.clearAllLeases();
        engine.clear();
        fixtures = new EmergencyFixtures(jdbc);
        fixtures.clean();
        base = fixtures.base();
        fixtures.givenScope(EmergencyFixtures.OPS, base.unitId());
        asAdmin();
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        InMemoryDistributedLock.clearAllLeases();
    }

    // ------------------------------------------------------------------ the whole path

    @Test
    void an_emergency_release_executes_through_a_freeze_and_writes_the_execute_audit() {
        Fixture f = productionFixture("CR-EMX-ONE", "PO-EMX-ONE", "PENDING_APPROVAL");
        fixtures.givenFreezeInForce(base.subsystemId());

        EmergencyResult released = emergencyRelease.release(f.publishNo(),
            EmergencyFixtures.REASON);
        assertThat(released.itemStatus()).isEqualTo("READY");

        asOps();
        ExecuteResult executed = executeProd.execute(f.publishNo());

        assertThat(executed.status()).isEqualTo("PROD_DEPLOYING");
        assertThat(executed.node()).isEqualTo("PROD_DEPLOY");
        assertThat(executed.batchCount()).isEqualTo(2);
        assertThat(itemStatus(f.itemId())).isEqualTo("PROD_DEPLOYING");
        assertThat(orderStatus(f.publishNo())).isEqualTo("EXECUTING");
        assertThat(instanceStatus(f.instanceId())).isEqualTo("RUNNING");
        assertThat(engine.triggeredDeNames())
            .as("the first batch runs although a freeze is in force")
            .containsExactly("em-prod-1");
        assertThat(boundary.blockedReason(f.instanceId()))
            .as("the batch boundary skips the freeze for the marked order").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action ="
            + " 'EXECUTE_PROD' AND object_id = ?", Integer.class, f.publishNo()))
            .as("the execute audit is written as before (design §5 item 4)").isEqualTo(1);
    }

    @Test
    void an_ordinary_release_is_still_blocked_by_the_same_freeze() {
        Fixture f = productionFixture("CR-EMX-NORMAL", "PO-EMX-NORMAL", "READY");
        fixtures.givenFreezeInForce(base.subsystemId());

        asOps();
        assertBizError(BizError.GATE_BLOCKED, () -> executeProd.execute(f.publishNo()));

        assertThat(itemStatus(f.itemId()))
            .as("a normal freeze pulls the ready release back into approval")
            .isEqualTo("PENDING_APPROVAL");
        assertThat(instanceStatus(f.instanceId())).isEqualTo("PAUSED");
        assertThat(engine.triggeredDeNames()).isEmpty();

        // The probe explains the same freeze at the batch boundary.
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", f.itemId());
        assertThat(boundary.blockedReason(f.instanceId())).contains("封板");
    }

    // ------------------------------------------------------------------ probe: gate stays

    @Test
    void the_batch_boundary_still_pauses_an_emergency_release_on_a_final_gate_block() {
        Fixture f = productionFixture("CR-EMX-GATE", "PO-EMX-GATE", "PENDING_APPROVAL");
        fixtures.givenFreezeInForce(base.subsystemId());
        // The fixture already records one passing SONAR check; turn the same row into a final block
        // (uk_item_check keeps one row per item/kind/stage).
        jdbc.update("UPDATE gate_check SET status = 'FAIL', severity = 'S1' WHERE item_id = ?"
            + " AND check_kind = 'SONAR'", f.itemId());
        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", f.itemId());

        assertThat(boundary.blockedReason(f.instanceId()))
            .as("the freeze is skipped but the non-waivable gate block is not")
            .contains("门禁");
    }

    // ------------------------------------------------------------------ review: other checks

    @Test
    void an_image_that_differs_from_the_snapshot_still_refuses_the_emergency_execution() {
        Fixture f = productionFixture("CR-EMX-IMAGE", "PO-EMX-IMAGE", "PENDING_APPROVAL");
        fixtures.givenFreezeInForce(base.subsystemId());
        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);
        jdbc.update("UPDATE pipeline_instance SET image_digest = 'sha256:rebuilt' WHERE id = ?",
            f.instanceId());

        asOps();
        assertBizError(BizError.DIGEST_MISMATCH, () -> executeProd.execute(f.publishNo()));
        assertThat(itemStatus(f.itemId())).isEqualTo("READY");
        assertThat(instanceStatus(f.instanceId())).isEqualTo("PAUSED");
        assertThat(engine.triggeredDeNames()).isEmpty();
    }

    @Test
    void an_expired_waiver_of_the_snapshot_still_refuses_the_emergency_execution() {
        Fixture f = productionFixture("CR-EMX-WAIVER", "PO-EMX-WAIVER", "PENDING_APPROVAL");
        fixtures.givenFreezeInForce(base.subsystemId());
        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);
        long waiverId = fixtures.givenWaiver(f.itemId(), f.orderId(), "APPROVED",
            Instant.now().minusSeconds(60));
        jdbc.update("UPDATE admission_snapshot SET waiver_ids = ? WHERE id = ?",
            "[" + waiverId + "]", f.snapshotId());

        asOps();
        assertBizError(BizError.GATE_BLOCKED, () -> executeProd.execute(f.publishNo()));
        assertThat(instanceStatus(f.instanceId())).isEqualTo("PAUSED");
        assertThat(engine.triggeredDeNames()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private record Fixture(long itemId, long orderId, long snapshotId, long instanceId,
                           String publishNo) {
    }

    /**
     * The state right before the first production batch: the 变更项 in the requested meeting state,
     * the order mirroring it, the admission snapshot whose digest is the instance's image, the
     * paused production instance with its two pre-generated batches, a passing check and the unit
     * lease. {@code PENDING_APPROVAL} also carries the two signing steps the emergency release voids.
     */
    private Fixture productionFixture(String crid, String publishNo, String itemStatus) {
        long itemId = fixtures.givenItem(base.taskId(), base.unitId(), crid,
            "release/emx-" + publishNo, itemStatus, "CHECK_PASS");
        jdbc.update("UPDATE release_item SET test_status = 'TEST_PASS' WHERE id = ?", itemId);
        long orderId = fixtures.givenOrder(itemId, publishNo, itemStatus);
        long snapshotId = fixtures.givenSnapshot(orderId, itemId, EmergencyFixtures.DEV,
            EmergencyFixtures.DIGEST);
        if ("PENDING_APPROVAL".equals(itemStatus)) {
            fixtures.givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
            fixtures.givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        }
        long instanceId = fixtures.givenInstance(orderId, "PL-EMX-" + publishNo, "PAUSED",
            EmergencyFixtures.DIGEST);
        fixtures.givenProductionWork(instanceId, base);
        fixtures.givenGateCheck(itemId, orderId, "PASS", "S3");
        unitLockService.acquire(base.unitId(), instanceId);
        return new Fixture(itemId, orderId, snapshotId, instanceId, publishNo);
    }

    private void asAdmin() {
        TestUserContext.as(EmergencyFixtures.ADMIN, Set.of(Roles.ADMIN), Set.of());
    }

    private void asOps() {
        TestUserContext.as(EmergencyFixtures.OPS, Set.of(Roles.OPS),
            Set.of(Permissions.CONTROL_EXECUTE_PROD));
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private String orderStatus(String publishNo) {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, publishNo);
    }

    private String instanceStatus(long instanceId) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private static void assertBizError(BizError expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(expected);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
        });
    }

    // ------------------------------------------------------------------ stub engine

    static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the emergency execution test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            return new TriggerResult("emx-dep-" + deployCalls.size(),
                "https://coding.example/deploy/" + deployCalls.size());
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the emergency execution test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the emergency execution test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the emergency execution test");
        }

        List<String> triggeredDeNames() {
            return deployCalls.stream().map(call -> call.cmd().deName()).toList();
        }

        void clear() {
            deployCalls.clear();
        }

        record DeployCall(DeployCmd cmd) {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine emergencyStubEngine() {
            return new StubEngine();
        }
    }
}