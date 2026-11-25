package com.sf.release.settings;

import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.GateService.CheckView;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.LibSecurityNodeHandler;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.gate.GateDecision;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.infra.job.UnitLeaseRenewJob;
import com.sf.release.infra.repo.DeployUnitLockMapper;
import com.sf.release.infra.repo.GateCheckMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The effects of the P6-B settings (design §2/§5), end to end against the real schema:
 *
 * <ul>
 *   <li>a disabled check produces no {@code VERIFY/CHECK} task for a new pipeline (online and
 *       rerun paths) and is not triggered by {@code GateCheckService};</li>
 *   <li>its historical {@code gate_check} row no longer blocks the evaluation, while the panel
 *       still shows the row marked {@code 已停用};</li>
 *   <li>the library security node ignores a disabled artifact check on both trigger and
 *       completeness;</li>
 *   <li>the lease renewer reads {@code pauseBoundHours} on every round and falls back to the
 *       configured environment bound when the row is missing/malformed;</li>
 *   <li>the gate consumer falls back to "all enabled" when its row is unreadable.</li>
 * </ul>
 *
 * <p>The package is {@code com.sf.release.settings} (not {@code ..web..}); the services are the
 * real Spring beans, and only the CI engine is a local Mockito stub for the trigger assertions.
 */
@SpringBootTest
@ActiveProfiles("test")
class SettingsEffectTest {

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired GateService gateService;
    @Autowired GateCheckService gateCheckService;
    @Autowired SettingsService settings;
    @Autowired PipelineRepository repository;
    @Autowired GateCheckMapper gateCheckMapper;
    @Autowired NodeTaskMapper nodeTaskMapper;
    @Autowired ReleaseItemMapper releaseItemMapper;
    @Autowired ReleaseOrderMapper releaseOrderMapper;
    @Autowired AuditService audit;
    @Autowired DeployUnitLockMapper lockMapper;
    @Autowired UnitLease unitLease;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    /** The publish path probes git; the probe is best-effort and irrelevant to these assertions. */
    @MockBean GitCommitProbe commitProbe;

    private long onlineUnitId;
    private long libUnitId;
    private long onlineItemId;
    private long libItemId;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(SettingsFixtures.OWNER);
        SettingsFixtures.clean(jdbc);
        SettingsFixtures.givenUser(jdbc, SettingsFixtures.OWNER);

        long subsystemId = SettingsFixtures.givenSubsystem(jdbc, "settings-subsystem");
        long taskId = SettingsFixtures.givenTask(jdbc, subsystemId, "settings-task");

        onlineUnitId = SettingsFixtures.givenUnit(jdbc, subsystemId, "settings-online");
        SettingsFixtures.givenBinding(jdbc, onlineUnitId, "BUILD", "P-BUILD-P6B");
        SettingsFixtures.givenBinding(jdbc, onlineUnitId, "TEST_DEPLOY", "P-TEST-P6B");
        SettingsFixtures.givenBinding(jdbc, onlineUnitId, "PROD_DEPLOY", "P-PROD-P6B");
        SettingsFixtures.givenDe(jdbc, onlineUnitId, "TEST", "settings-sit", 0);
        SettingsFixtures.givenDe(jdbc, onlineUnitId, "PROD", "settings-prod", 1);
        onlineItemId = itemService.create(taskId,
            new CreateItemCmd(onlineUnitId, SettingsFixtures.BRANCH, "online item",
                SettingsFixtures.PLAN_DATE));

        libUnitId = SettingsFixtures.givenLibUnit(jdbc, subsystemId, "settings-lib");
        SettingsFixtures.givenBinding(jdbc, libUnitId, "BUILD", "P-BUILD-LIB-P6B");
        SettingsFixtures.givenBinding(jdbc, libUnitId, "LIB_PUBLISH", "P-PUBLISH-LIB-P6B");
        libItemId = itemService.create(taskId,
            new CreateItemCmd(libUnitId, SettingsFixtures.BRANCH, SettingsFixtures.VERSION,
                SettingsFixtures.PLAN_DATE));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        SettingsFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ new instances

    @Test
    void a_disabled_check_is_not_pre_generated_for_a_new_online_pipeline() {
        settings.saveGate(namesWithout(CheckType.SONAR));

        PublishResult result = publishService.publish(onlineItemId);
        long instanceId = instanceId(result.pipelineNo());

        assertThat(checkTargets(instanceId))
            .as("the VERIFY node carries only the enabled CHECK tasks")
            .containsExactly(CheckType.COMPONENT_VULN.code(), CheckType.ARCHITECTURE.code(),
                CheckType.VERSION_DEFECT.code(), CheckType.SECURITY_DYNAMIC.code(),
                CheckType.AUTO_TEST.code(), CheckType.DB_NORM.code());
    }

    @Test
    void the_check_trigger_skips_a_disabled_check_but_starts_the_rest() {
        PublishResult result = publishService.publish(onlineItemId);
        long instanceId = instanceId(result.pipelineNo());
        settings.saveGate(namesWithout(CheckType.SONAR));

        CicdEngine engine = stubEngine();
        GateCheckService local = checkService(engine);
        int started = local.startChecks(repository.loadInstance(instanceId));

        assertThat(started).isEqualTo(6);
        verify(engine, times(6)).startCheck(any());
        assertThat(checkKinds(onlineItemId)).doesNotContain("SONAR").hasSize(6);
    }

    // ------------------------------------------------------------------ evaluation

    @Test
    void a_disabled_checks_historical_failure_no_longer_blocks_the_gate() {
        PublishResult result = publishService.publish(onlineItemId);
        long instanceId = instanceId(result.pipelineNo());
        givenGateChecks(onlineItemId);

        GateDecision blocked = gateService.decisionFor(onlineItemId);
        assertThat(blocked.conclusion())
            .as("the historical 高危 SONAR row blocks while the check is enabled")
            .isEqualTo(GateConclusion.BLOCK);
        assertThat(blocked.blocking()).isPositive();

        settings.saveGate(namesWithout(CheckType.SONAR));

        GateDecision passed = gateService.decisionFor(onlineItemId);
        assertThat(passed.conclusion()).isEqualTo(GateConclusion.PASS);
        assertThat(passed.blocking()).isZero();

        // The row is history, not deleted: the panel still shows it, tagged as disabled.
        assertThat(checkKinds(onlineItemId)).contains("SONAR");
        CheckView sonar = checkViews(onlineItemId).stream()
            .filter(view -> "SONAR".equals(view.kind()))
            .findFirst().orElseThrow();
        assertThat(sonar.disabled()).isTrue();
        assertThat(sonar.disabledLabel()).isEqualTo("已停用");
        assertThat(sonar.status()).isEqualTo("FAIL");
        assertThat(instanceId).isPositive();
    }

    @Test
    void disabling_every_artifact_check_fails_the_library_security_node_instead_of_stalling_it() {
        settings.saveGate(List.of(CheckType.AUTO_TEST.name(), CheckType.DB_NORM.name()));

        PublishResult result = publishService.publish(libItemId);
        long instanceId = instanceId(result.pipelineNo());

        assertThat(gateService.libSecuritySatisfied(repository.loadInstance(instanceId)))
            .isFalse();
        assertThat(gateService.libSecurityBlockedReason(repository.loadInstance(instanceId)))
            .as("with no applicable artifact check the node must fail fast, not wait forever")
            .contains("全部停用");
    }

    // ------------------------------------------------------------------ library path

    @Test
    void the_library_security_node_skips_a_disabled_check_on_trigger_and_completeness() {
        PublishResult result = publishService.publish(libItemId);
        long instanceId = instanceId(result.pipelineNo());
        settings.saveGate(namesWithout(CheckType.VERSION_DEFECT));

        CicdEngine engine = stubEngine();
        GateCheckService local = checkService(engine);
        LibSecurityNodeHandler handler = new LibSecurityNodeHandler(local, gateService, repository);
        handler.start(repository.loadInstance(instanceId));

        ArgumentCaptor<GateCheckCmd> captor = ArgumentCaptor.forClass(GateCheckCmd.class);
        verify(engine, times(2)).startCheck(captor.capture());
        assertThat(captor.getAllValues().stream().map(GateCheckCmd::checkType).toList())
            .containsExactlyInAnyOrder(CheckType.SONAR, CheckType.COMPONENT_VULN);
        assertThat(checkKinds(libItemId))
            .containsExactlyInAnyOrder("SONAR", "COMPONENT_VULN");

        jdbc.update("UPDATE gate_check SET status = 'PASS' WHERE item_id = ?", libItemId);
        assertThat(gateService.libSecuritySatisfied(repository.loadInstance(instanceId)))
            .as("the disabled artifact check is not required by the completeness guard")
            .isTrue();

        SettingsFixtures.resetSettings(jdbc);
        assertThat(gateService.libSecuritySatisfied(repository.loadInstance(instanceId)))
            .as("with every check enabled, the missing VERSION_DEFECT row keeps the node waiting")
            .isFalse();
    }

    // ------------------------------------------------------------------ lock bound

    @Test
    void the_renewer_reads_the_saved_pause_bound_on_every_round() {
        Instant pausedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        SettingsFixtures.givenPausedInstance(jdbc, onlineUnitId, "PL-P6B-RENEW", pausedAt);
        UnitLeaseRenewJob job = new UnitLeaseRenewJob(lockMapper, unitLease, null,
            Duration.ofHours(1), settings);

        settings.saveLock(3);
        job.renewLeases();
        assertThat(SettingsFixtures.lockExpireAt(jdbc, onlineUnitId))
            .as("a two-hour pause is inside the saved three-hour bound")
            .isAfter(Instant.now().plusSeconds(3 * 3600));

        Instant near = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        SettingsFixtures.setLockExpireAt(jdbc, onlineUnitId, near);
        settings.saveLock(1);
        job.renewLeases();
        assertThat(SettingsFixtures.lockExpireAt(jdbc, onlineUnitId))
            .as("the same job instance uses the new one-hour bound on the next round")
            .isEqualTo(near);
    }

    @Test
    void an_unreadable_lock_row_falls_back_to_the_configured_environment_bound() {
        Instant pausedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        SettingsFixtures.givenPausedInstance(jdbc, onlineUnitId, "PL-P6B-LOCK-FALLBACK", pausedAt);
        Instant near = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        SettingsFixtures.setLockExpireAt(jdbc, onlineUnitId, near);
        UnitLeaseRenewJob job = new UnitLeaseRenewJob(lockMapper, unitLease, null,
            Duration.ofHours(1), settings);

        // Valid JSON of the wrong shape: the row is readable, the section is not.
        jdbc.update("UPDATE app_setting SET value_json = '[]' WHERE setting_key = 'LOCK'");
        job.renewLeases();
        assertThat(SettingsFixtures.lockExpireAt(jdbc, onlineUnitId))
            .as("the environment's one-hour bound is used when the section is malformed")
            .isEqualTo(near);

        SettingsFixtures.setLockExpireAt(jdbc, onlineUnitId, near);
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'LOCK'");
        job.renewLeases();
        assertThat(SettingsFixtures.lockExpireAt(jdbc, onlineUnitId))
            .as("the environment's one-hour bound is used when the row is missing")
            .isEqualTo(near);
    }

    @Test
    void an_unreadable_gate_row_keeps_every_check_enabled() {
        jdbc.update("UPDATE app_setting SET value_json = '[]' WHERE setting_key = 'GATE'");
        assertThat(settings.enabledChecks())
            .as("a malformed section must never silently disable checks")
            .containsExactlyInAnyOrder(CheckType.values());

        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'GATE'");
        assertThat(settings.enabledChecks()).containsExactlyInAnyOrder(CheckType.values());
    }

    // ------------------------------------------------------------------ helpers

    private GateCheckService checkService(CicdEngine engine) {
        return new GateCheckService(engine, gateCheckMapper, nodeTaskMapper, releaseItemMapper,
            releaseOrderMapper, repository, audit, transactionManager, settings);
    }

    /** A recording engine stub: every check trigger succeeds with a distinct external id. */
    private static CicdEngine stubEngine() {
        CicdEngine engine = mock(CicdEngine.class);
        AtomicInteger seq = new AtomicInteger();
        when(engine.startCheck(any())).thenAnswer(invocation -> {
            int next = seq.incrementAndGet();
            return new TriggerResult("check-" + next, "https://engine.example/check/" + next);
        });
        return engine;
    }

    private static List<String> namesWithout(CheckType disabled) {
        List<String> names = new ArrayList<>();
        for (CheckType type : CheckType.values()) {
            if (type != disabled) {
                names.add(type.name());
            }
        }
        return names;
    }

    private long instanceId(String pipelineNo) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private List<Integer> checkTargets(long instanceId) {
        return jdbc.queryForList("SELECT target_id FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'VERIFY' AND task_type = 'CHECK' ORDER BY target_id",
            Integer.class, instanceId);
    }

    private List<String> checkKinds(long itemId) {
        return jdbc.queryForList("SELECT check_kind FROM gate_check WHERE item_id = ?"
            + " ORDER BY check_kind", String.class, itemId);
    }

    private List<CheckView> checkViews(long itemId) {
        List<CheckView> views = new ArrayList<>();
        for (GateService.CheckGroup group : gateService.view(itemId).groups()) {
            views.addAll(group.checks());
        }
        return views;
    }

    /**
     * One row per check: SONAR fails with a 高危 finding, every other check passes. This is the
     * "history" the switch must ignore without deleting.
     */
    private void givenGateChecks(long itemId) {
        for (CheckType type : CheckType.values()) {
            boolean failing = type == CheckType.SONAR;
            SettingsFixtures.givenGateCheck(jdbc, itemId, type.name(), type.stage().name(),
                failing ? "FAIL" : "PASS", failing ? "S1" : "NONE");
        }
    }
}