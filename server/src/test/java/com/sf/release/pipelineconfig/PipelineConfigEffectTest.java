package com.sf.release.pipelineconfig;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.GateCheckService;
import com.sf.release.application.GateService;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.LibPublishNodeHandler;
import com.sf.release.application.LibSecurityNodeHandler;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.settings.PipelineConfigService;
import com.sf.release.application.settings.SettingsService;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.PublishCmd;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.pipeline.CheckType;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineFactory;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.TriggerType;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
import com.sf.release.domain.pipeline.handler.DailyDeployNodeHandler;
import com.sf.release.domain.pipeline.handler.ProdDeployNodeHandler;
import com.sf.release.domain.pipeline.handler.TestDeployNodeHandler;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.domain.pipeline.port.UnitLease;
import com.sf.release.domain.resource.EnvDe;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.domain.pipeline.ReleaseOrder;
import com.sf.release.infra.repo.GateCheckMapper;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The effects of the P6-C unit pipeline configuration (design §2/§5 items 3, 4, 5 and 8), end to
 * end against the real schema:
 *
 * <ul>
 *   <li>the environment-check switch removes exactly the three environment tasks of a new online
 *       instance and is intersected with the platform's enabled set;</li>
 *   <li>the evaluation ignores the disabled environment rows immediately; the library path is
 *       unaffected;</li>
 *   <li>build/test/production/daily deadlines use the configured minutes and fall back to the
 *       pre-P6-C constants when there is no row; an in-flight deadline is never recalculated;</li>
 *   <li>{@code REVERSE} renumbers the production batches of a new online instance only; an
 *       existing instance and the rollback factory keep their own order.</li>
 * </ul>
 *
 * <p>The package is {@code com.sf.release.pipelineconfig}, not {@code ..web..}. The pipeline is
 * driven through the real orchestrator/handlers with a recording engine stub, exactly like the
 * P1/P2/P4/P5 handler tests; the suite's existing pipeline tests are the regression half of design
 * §5 item 8.
 */
@SpringBootTest
@ActiveProfiles("test")
class PipelineConfigEffectTest {

    private static final String BUILD_PLAN = "P-BUILD-P6C";
    private static final String TEST_PLAN = "P-TEST-P6C";
    private static final String PROD_PLAN = "P-PROD-P6C";
    private static final String LIB_PUBLISH_PLAN = "P-PUBLISH-P6C";
    private static final String DIGEST = "sha256:p6c";

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired DailyReleaseService dailyService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired PipelineEventBus eventBus;
    @Autowired GateService gateService;
    @Autowired GateCheckService gateCheckService;
    @Autowired SettingsService settings;
    @Autowired PipelineConfigService pipelineConfigs;
    @Autowired UnitLease unitLease;
    @Autowired AuditService audit;
    @Autowired GateCheckMapper gateCheckMapper;
    @Autowired NodeTaskMapper nodeTaskMapper;
    @Autowired ReleaseItemMapper releaseItemMapper;
    @Autowired ReleaseOrderMapper releaseOrderMapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    /** The publish path probes git; the probe is best-effort and irrelevant to these assertions. */
    @MockBean GitCommitProbe commitProbe;

    private RecordingEngine engine;
    private long subsystemId;
    private long taskId;
    private long unitId;
    private long testDeId;
    private long prodDe1;
    private long prodDe2;
    private long prodDe3;
    private long libUnitId;
    private long itemId;
    private long libItemId;
    private int itemSequence;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(PipelineConfigFixtures.OWNER);
        PipelineConfigFixtures.clean(jdbc);
        PipelineConfigFixtures.givenUser(jdbc, PipelineConfigFixtures.OWNER);
        engine = new RecordingEngine();
        handlers.clear();

        subsystemId = PipelineConfigFixtures.givenSubsystem(jdbc, "p6c-effect");
        taskId = PipelineConfigFixtures.givenTask(jdbc, subsystemId, "p6c-effect-task");

        unitId = PipelineConfigFixtures.givenUnit(jdbc, subsystemId, "p6c-effect-api");
        PipelineConfigFixtures.givenBinding(jdbc, unitId, "BUILD", BUILD_PLAN);
        PipelineConfigFixtures.givenBinding(jdbc, unitId, "TEST_DEPLOY", TEST_PLAN);
        PipelineConfigFixtures.givenBinding(jdbc, unitId, "PROD_DEPLOY", PROD_PLAN);
        testDeId = PipelineConfigFixtures.givenDe(jdbc, unitId, "TEST", "p6c-sit", 0);
        prodDe1 = PipelineConfigFixtures.givenDe(jdbc, unitId, "PROD", "p6c-prod-1", 1);
        prodDe2 = PipelineConfigFixtures.givenDe(jdbc, unitId, "PROD", "p6c-prod-2", 2);
        prodDe3 = PipelineConfigFixtures.givenDe(jdbc, unitId, "PROD", "p6c-prod-3", 3);
        itemId = itemService.create(taskId, new CreateItemCmd(unitId,
            PipelineConfigFixtures.BRANCH, "online item", PipelineConfigFixtures.PLAN_DATE));

        libUnitId = PipelineConfigFixtures.givenLibUnit(jdbc, subsystemId, "p6c-effect-lib");
        PipelineConfigFixtures.givenBinding(jdbc, libUnitId, "BUILD", "P-BUILD-LIB-P6C");
        PipelineConfigFixtures.givenBinding(jdbc, libUnitId, "LIB_PUBLISH", LIB_PUBLISH_PLAN);
        libItemId = itemService.create(taskId, new CreateItemCmd(libUnitId,
            PipelineConfigFixtures.BRANCH, PipelineConfigFixtures.VERSION,
            PipelineConfigFixtures.PLAN_DATE));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
        jdbc.update("DELETE FROM unit_pipeline_config");
        PipelineConfigFixtures.resetSettings(jdbc);
    }

    // ------------------------------------------------------------------ environment checks

    @Test
    void the_environment_switch_removes_only_the_three_environment_tasks() {
        PipelineConfigFixtures.givenConfig(jdbc, unitId, false, 60, 8, "FORWARD");

        long instanceId = publish(itemId);

        assertThat(checkTargets(instanceId))
            .as("the four image-level checks stay, the three environment checks are gone")
            .containsExactly(CheckType.SONAR.code(), CheckType.COMPONENT_VULN.code(),
                CheckType.ARCHITECTURE.code(), CheckType.VERSION_DEFECT.code());
        assertThat(checkTargets(instanceId))
            .doesNotContainAnyElementsOf(UnitPipelineConfig.environmentChecks().stream()
                .map(CheckType::code).toList());
    }

    @Test
    void the_unit_switch_is_intersected_with_the_platform_enabled_set() {
        settings.saveGate(namesWithout(CheckType.SONAR, CheckType.AUTO_TEST));
        PipelineConfigFixtures.givenConfig(jdbc, unitId, false, 60, 8, "FORWARD");

        long instanceId = publish(itemId);

        assertThat(checkTargets(instanceId))
            .as("platform enabled minus SONAR, then minus the three environment checks")
            .containsExactly(CheckType.COMPONENT_VULN.code(), CheckType.ARCHITECTURE.code(),
                CheckType.VERSION_DEFECT.code());
    }

    @Test
    void disabling_every_environment_check_leaves_the_platform_image_checks_runnable() {
        settings.saveGate(List.of(CheckType.AUTO_TEST.name()));
        PipelineConfigFixtures.givenConfig(jdbc, unitId, false, 60, 8, "FORWARD");

        assertThatThrownBy(() -> publishService.publish(itemId))
            .isInstanceOf(com.sf.release.common.BizException.class)
            .hasMessageContaining("没有可执行的检查项")
            .satisfies(error -> assertThat(
                ((com.sf.release.common.BizException) error).getError().getCode())
                .isEqualTo(40011));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_instance", Integer.class))
            .as("the refused publish must not leave a half-created instance")
            .isZero();
    }

    @Test
    void the_evaluation_ignores_disabled_environment_rows_immediately() {
        long instanceId = publish(itemId);
        givenGateChecks(itemId, CheckType.AUTO_TEST);

        assertThat(gateService.decisionFor(itemId).conclusion())
            .as("the historical high-severity AUTO_TEST row blocks while the check is enabled")
            .isEqualTo(GateConclusion.BLOCK);

        pipelineConfigs.save(unitId, "false", "60", "8", "FORWARD");

        assertThat(gateService.decisionFor(itemId).conclusion())
            .isEqualTo(GateConclusion.PASS);
        assertThat(gateService.decisionFor(itemId).blocking()).isZero();

        GateService.CheckView autoTest = gateService.view(itemId).groups().stream()
            .flatMap(group -> group.checks().stream())
            .filter(view -> "AUTO_TEST".equals(view.kind()))
            .findFirst().orElseThrow();
        assertThat(autoTest.disabled()).isTrue();
        assertThat(autoTest.disabledLabel()).isEqualTo("已停用");
        assertThat(instanceId).isPositive();
    }

    @Test
    void a_library_unit_with_the_switch_off_still_runs_its_three_artifact_checks() {
        PipelineConfigFixtures.givenConfig(jdbc, libUnitId, false, 60, 8, "FORWARD");
        long instanceId = publish(libItemId);

        GateCheckService localChecks = new GateCheckService(engine, gateCheckMapper,
            nodeTaskMapper, releaseItemMapper, releaseOrderMapper, repository, audit,
            transactionManager, settings);
        LibSecurityNodeHandler handler = new LibSecurityNodeHandler(localChecks, gateService,
            repository);
        handler.start(repository.loadInstance(instanceId));

        assertThat(engine.checkKinds())
            .as("a library has no environment stage, so the switch cannot remove its checks")
            .containsExactlyInAnyOrder(CheckType.SONAR, CheckType.COMPONENT_VULN,
                CheckType.VERSION_DEFECT);
        assertThat(repository.pipelineConfigOf(instanceId).envChecksEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'VERIFY'", Integer.class, instanceId)).isZero();
    }

    // ------------------------------------------------------------------ deadlines

    @Test
    void a_build_step_uses_the_configured_build_timeout() {
        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 15, 3, "FORWARD");
        long instanceId = publish(itemId);

        advanceBuild(instanceId);

        assertDeadlineMinutes(instanceId, NodeType.BUILD, TaskType.CODE_CHECK, null, 15);
    }

    @Test
    void test_production_and_daily_deploys_use_the_configured_deploy_timeout() {
        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 15, 3, "FORWARD");

        long online = publishFresh();
        updateInstance(online, "RUNNING", "TEST_DEPLOY", DIGEST);
        testDeployHandler().start(repository.loadInstance(online));
        assertDeadlineMinutes(online, NodeType.TEST_DEPLOY, TaskType.DEPLOY, testDeId, 3);

        long production = publishFresh();
        updateInstance(production, "RUNNING", "PROD_DEPLOY", DIGEST);
        prodDeployHandler().start(repository.loadInstance(production));
        assertDeadlineMinutes(production, NodeType.PROD_DEPLOY, TaskType.DEPLOY, prodDe1, 3);

        long daily = startDaily();
        advanceBuild(daily);
        assertDeadlineMinutes(daily, NodeType.BUILD, TaskType.CODE_CHECK, null, 15);
        jdbc.update("UPDATE pipeline_instance SET image_digest = ? WHERE id = ?", DIGEST, daily);
        new DailyDeployNodeHandler(engine, repository, transactionRunner, completionService)
            .start(repository.loadInstance(daily));
        assertDeadlineMinutes(daily, NodeType.DAILY_DEPLOY, TaskType.DEPLOY, testDeId, 3);
    }

    @Test
    void a_library_publish_uses_the_build_timeout_and_falls_back_to_its_constant() {
        PipelineConfigFixtures.givenConfig(jdbc, libUnitId, true, 15, 3, "FORWARD");
        long configured = publishFreshLib();
        updateInstance(configured, "RUNNING", "LIB_PUBLISH", DIGEST);
        libPublishHandler().start(repository.loadInstance(configured));
        assertDeadlineMinutes(configured, NodeType.LIB_PUBLISH, TaskType.LIB_PUBLISH, null, 15);

        jdbc.update("DELETE FROM unit_pipeline_config WHERE unit_id = ?", libUnitId);
        long fallback = publishFreshLib();
        updateInstance(fallback, "RUNNING", "LIB_PUBLISH", DIGEST);
        libPublishHandler().start(repository.loadInstance(fallback));
        assertDeadlineMinutes(fallback, NodeType.LIB_PUBLISH, TaskType.LIB_PUBLISH, null, 60);
    }

    @Test
    void without_a_row_every_deadline_keeps_its_pre_p6c_constant() {
        long online = publishFresh();
        advanceBuild(online);
        assertDeadlineMinutes(online, NodeType.BUILD, TaskType.CODE_CHECK, null, 60);

        long testDeploy = publishFresh();
        updateInstance(testDeploy, "RUNNING", "TEST_DEPLOY", DIGEST);
        testDeployHandler().start(repository.loadInstance(testDeploy));
        assertDeadlineMinutes(testDeploy, NodeType.TEST_DEPLOY, TaskType.DEPLOY, testDeId, 8);

        long production = publishFresh();
        updateInstance(production, "RUNNING", "PROD_DEPLOY", DIGEST);
        prodDeployHandler().start(repository.loadInstance(production));
        assertDeadlineMinutes(production, NodeType.PROD_DEPLOY, TaskType.DEPLOY, prodDe1, 8);

        long daily = startDaily();
        advanceBuild(daily);
        assertDeadlineMinutes(daily, NodeType.BUILD, TaskType.CODE_CHECK, null, 60);
        jdbc.update("UPDATE pipeline_instance SET image_digest = ? WHERE id = ?", DIGEST, daily);
        new DailyDeployNodeHandler(engine, repository, transactionRunner, completionService)
            .start(repository.loadInstance(daily));
        assertThat(repository.pipelineConfigOf(daily))
            .as("no row is exactly what the handlers must degrade")
            .isNull();
        assertDeadlineMinutes(daily, NodeType.DAILY_DEPLOY, TaskType.DEPLOY, testDeId, 60);
    }

    @Test
    void an_in_flight_deadline_is_never_recalculated_but_the_next_step_reads_the_new_value() {
        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 15, 3, "FORWARD");
        long instanceId = publish(itemId);
        advanceBuild(instanceId);
        Instant firstDeadline = deadline(instanceId, NodeType.BUILD, TaskType.CODE_CHECK, null);

        pipelineConfigs.save(unitId, "true", "30", "8", "FORWARD");
        assertThat(deadline(instanceId, NodeType.BUILD, TaskType.CODE_CHECK, null))
            .as("a save must never rewrite the deadline of a task already waiting")
            .isEqualTo(firstDeadline);

        long codeCheck = latestTaskId(instanceId, NodeType.BUILD, TaskType.CODE_CHECK, null);
        orchestrator.onTaskCompleted(codeCheck, NodeTaskStatus.SUCCESS, null, null);
        assertDeadlineMinutes(instanceId, NodeType.BUILD, TaskType.PKG_BUILD, null, 30);
    }

    // ------------------------------------------------------------------ batch order

    @Test
    void reverse_batch_order_renumbers_the_new_online_production_tasks() {
        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 60, 8, "REVERSE");

        long instanceId = publish(itemId);

        assertThat(productionBatches(instanceId))
            .as("original batch 1 runs last, original batch 3 runs first")
            .containsEntry(prodDe1, 3)
            .containsEntry(prodDe2, 2)
            .containsEntry(prodDe3, 1);
        assertThat(testBatch(instanceId))
            .as("the test deploy is not batched and is unaffected")
            .isZero();
    }

    @Test
    void forward_and_absent_configuration_keep_the_original_batch_numbers() {
        long withoutRow = publishFresh();
        assertThat(productionBatches(withoutRow))
            .containsEntry(prodDe1, 1).containsEntry(prodDe2, 2).containsEntry(prodDe3, 3);

        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 60, 8, "FORWARD");
        long forward = publishFresh();
        assertThat(productionBatches(forward))
            .containsEntry(prodDe1, 1).containsEntry(prodDe2, 2).containsEntry(prodDe3, 3);
    }

    @Test
    void an_existing_instance_is_not_renumbered_when_the_order_is_saved() {
        long instanceId = publish(itemId);
        Map<Long, Integer> before = productionBatches(instanceId);

        pipelineConfigs.save(unitId, "true", "60", "8", "REVERSE");

        assertThat(productionBatches(instanceId)).isEqualTo(before);
        assertThat(repository.pipelineConfigOf(instanceId).batchOrder().name()).isEqualTo("REVERSE");
    }

    @Test
    void the_rollback_factory_keeps_its_own_reverse_order_whatever_the_config_says() {
        publish(itemId);
        Long orderId = jdbc.queryForObject(
            "SELECT order_id FROM pipeline_instance ORDER BY id DESC LIMIT 1", Long.class);
        ReleaseOrder order = releaseOrderMapper.selectById(orderId).toDomain();
        ReleaseItem item = releaseItemMapper.selectById(itemId).toDomain();
        List<EnvDe> envDes = PipelineConfigFixtures.envDesOf(jdbc, unitId);

        PipelineConfigFixtures.givenConfig(jdbc, unitId, true, 60, 8, "FORWARD");
        Map<Long, Integer> forwardConfig = rollbackBatches(order, item, envDes);
        jdbc.update("UPDATE unit_pipeline_config SET batch_order = 'REVERSE' WHERE unit_id = ?",
            unitId);
        Map<Long, Integer> reverseConfig = rollbackBatches(order, item, envDes);

        assertThat(forwardConfig)
            .as("a rollback always reverses its batches: 1,2,3 -> 3,2,1")
            .containsEntry(prodDe1, 3).containsEntry(prodDe2, 2).containsEntry(prodDe3, 1);
        assertThat(reverseConfig)
            .as("the unit configuration is not read by the rollback path")
            .isEqualTo(forwardConfig);
    }

    private Map<Long, Integer> rollbackBatches(ReleaseOrder order, ReleaseItem item,
                                               List<EnvDe> envDes) {
        PipelineInstance rollback = PipelineFactory.createRollback(order, item, envDes,
            "sha256:rollback-target", Instant.now());
        assertThat(rollback.triggerType()).isEqualTo(TriggerType.ROLLBACK);
        Map<Long, Integer> batches = new LinkedHashMap<>();
        for (var task : rollback.node(NodeType.PROD_DEPLOY).tasks()) {
            batches.put(task.targetId(), task.batchNo());
        }
        return batches;
    }

    // ------------------------------------------------------------------ helpers

    private long publish(long id) {
        PublishResult result = publishService.publish(id);
        return instanceId(result.pipelineNo());
    }

    /** One fresh online item of the test unit (a published item cannot be published twice). */
    private long publishFresh() {
        // A previous publish of the same unit still holds the unit lock; this scenario starts a
        // second pipeline, so the reservation is released first. The handler assertions do not
        // depend on the unit lock (the orchestrator uses its own pipeline lock).
        releaseUnitLock(unitId);
        return publish(newItem(unitId));
    }

    private long newItem(long unit) {
        itemSequence++;
        return itemService.create(taskId, new CreateItemCmd(unit,
            PipelineConfigFixtures.BRANCH + "-" + itemSequence, "p6c-" + itemSequence,
            PipelineConfigFixtures.PLAN_DATE));
    }

    private long publishFreshLib() {
        releaseUnitLock(libUnitId);
        itemSequence++;
        long id = itemService.create(taskId, new CreateItemCmd(libUnitId,
            PipelineConfigFixtures.BRANCH + "-lib-" + itemSequence,
            PipelineConfigFixtures.VERSION + "-" + itemSequence,
            PipelineConfigFixtures.PLAN_DATE));
        return publish(id);
    }

    /** Frees the unit reservation (both the DB row and the distributed key) between publishes. */
    private void releaseUnitLock(long unit) {
        List<Long> owners = jdbc.queryForList(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unit);
        Long owner = owners.isEmpty() ? null : owners.get(0);
        if (owner != null && owner > 0) {
            unitLease.release(unit, owner);
        } else {
            jdbc.update("DELETE FROM deploy_unit_lock WHERE unit_id = ?", unit);
        }
    }

    private long startDaily() {
        TestUserContext.as(PipelineConfigFixtures.OWNER, Set.of("ADMIN"),
            Set.of(Permissions.RELEASE_DAILY));
        DailyReleaseService.StartResult result = dailyService.start(itemId);
        return result.instanceId();
    }

    private void advanceBuild(long instanceId) {
        handlers.clear();
        handlers.register(new BuildNodeHandler(engine, repository, transactionRunner,
            completionService));
        handlers.register(noop(NodeType.TEST_DEPLOY));
        handlers.register(noop(NodeType.VERIFY));
        handlers.register(noop(NodeType.PROD_DEPLOY));
        orchestrator.advance(instanceId);
    }

    private TestDeployNodeHandler testDeployHandler() {
        return new TestDeployNodeHandler(engine, repository, transactionRunner,
            completionService, eventBus);
    }

    private ProdDeployNodeHandler prodDeployHandler() {
        return new ProdDeployNodeHandler(engine, repository, transactionRunner,
            completionService, instanceId -> null);
    }

    private LibPublishNodeHandler libPublishHandler() {
        return new LibPublishNodeHandler(engine, repository, transactionRunner,
            completionService);
    }

    private static NodeHandler noop(NodeType type) {
        return new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return type;
            }

            @Override
            public void start(PipelineInstance instance) {
            }
        };
    }

    private void updateInstance(long instanceId, String status, String currentNode,
                                String imageDigest) {
        jdbc.update("UPDATE pipeline_instance SET status = ?, current_node = ?,"
            + " image_digest = ? WHERE id = ?", status, currentNode, imageDigest, instanceId);
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

    private Map<Long, Integer> productionBatches(long instanceId) {
        Map<Long, Integer> batches = new LinkedHashMap<>();
        jdbc.query("SELECT target_id, batch_no FROM node_task WHERE instance_id = ?"
                + " AND node_type = 'PROD_DEPLOY' AND task_type = 'DEPLOY' ORDER BY target_id",
            rs -> {
                batches.put(rs.getLong("target_id"), rs.getInt("batch_no"));
            }, instanceId);
        return batches;
    }

    private int testBatch(long instanceId) {
        Integer batch = jdbc.queryForObject("SELECT batch_no FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'TEST_DEPLOY' AND task_type = 'DEPLOY' AND target_id = ?",
            Integer.class, instanceId, testDeId);
        return batch == null ? -1 : batch;
    }

    private long latestTaskId(long instanceId, NodeType nodeType, TaskType taskType,
                              Long targetId) {
        String suffix = targetId == null ? "" : " AND target_id = " + targetId;
        Long id = jdbc.queryForObject("SELECT id FROM node_task WHERE instance_id = ?"
            + " AND node_type = ? AND task_type = ?" + suffix + " ORDER BY attempt DESC, id DESC"
            + " LIMIT 1", Long.class, instanceId, nodeType.name(), taskType.name());
        return id == null ? 0L : id;
    }

    private Instant deadline(long instanceId, NodeType nodeType, TaskType taskType,
                             Long targetId) {
        String suffix = targetId == null ? "" : " AND target_id = " + targetId;
        Timestamp value = jdbc.queryForObject("SELECT deadline_at FROM node_task"
            + " WHERE instance_id = ? AND node_type = ? AND task_type = ?" + suffix
            + " ORDER BY id DESC LIMIT 1", Timestamp.class, instanceId, nodeType.name(),
            taskType.name());
        return value == null ? null : value.toInstant();
    }

    private void assertDeadlineMinutes(long instanceId, NodeType nodeType, TaskType taskType,
                                       Long targetId, int minutes) {
        String suffix = targetId == null ? "" : " AND target_id = " + targetId;
        Instant[] times = jdbc.queryForObject("SELECT started_at, deadline_at FROM node_task"
                + " WHERE instance_id = ? AND node_type = ? AND task_type = ?" + suffix
                + " ORDER BY id DESC LIMIT 1",
            (rs, rowNum) -> new Instant[]{rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant()},
            instanceId, nodeType.name(), taskType.name());
        assertThat(times).as("the task must have been triggered").doesNotContainNull();
        Duration actual = Duration.between(times[0], times[1]);
        assertThat(actual)
            .as("%s/%s deadline should be %s minutes from the task start but was %s",
                nodeType, taskType, minutes, actual)
            .isBetween(Duration.ofMinutes(minutes).minusSeconds(5),
                Duration.ofMinutes(minutes).plusSeconds(5));
    }

    private void givenGateChecks(long itemId, CheckType failing) {
        for (CheckType type : CheckType.values()) {
            boolean fail = type == failing;
            jdbc.update("INSERT INTO gate_check (item_id, check_kind, stage, status, severity,"
                    + " issues, attempt, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, 0, 0, NOW(3), NOW(3))",
                itemId, type.name(), type.stage().name(), fail ? "FAIL" : "PASS",
                fail ? "S1" : "NONE");
        }
    }

    private static List<String> namesWithout(CheckType... disabled) {
        Set<CheckType> excluded = Set.of(disabled);
        List<String> names = new ArrayList<>();
        for (CheckType type : CheckType.values()) {
            if (!excluded.contains(type)) {
                names.add(type.name());
            }
        }
        return names;
    }

    /** A recording engine stub: every build/deploy/publish/check trigger succeeds. */
    private static final class RecordingEngine implements CicdEngine {

        private final AtomicInteger seq = new AtomicInteger();
        private final List<GateCheckCmd> checks = new ArrayList<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            int next = seq.incrementAndGet();
            return new TriggerResult("build-" + next, "https://engine.example/build/" + next);
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            int next = seq.incrementAndGet();
            return new TriggerResult("deploy-" + next, "https://engine.example/deploy/" + next);
        }

        @Override
        public TriggerResult startPublish(PublishCmd cmd) {
            int next = seq.incrementAndGet();
            return new TriggerResult("publish-" + next, "https://engine.example/publish/" + next);
        }

        @Override
        public TriggerResult startCheck(GateCheckCmd cmd) {
            checks.add(cmd);
            int next = seq.incrementAndGet();
            return new TriggerResult("check-" + next, "https://engine.example/check/" + next);
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the pipeline config test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the pipeline config test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the pipeline config test");
        }

        List<CheckType> checkKinds() {
            return checks.stream().map(GateCheckCmd::checkType).toList();
        }
    }
}