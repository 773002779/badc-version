package com.sf.release.daily;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.DailyReleaseService.DailyDetail;
import com.sf.release.application.DailyReleaseService.DailyPage;
import com.sf.release.application.DailyReleaseService.DailyRecord;
import com.sf.release.application.DailyReleaseService.DailyTask;
import com.sf.release.application.DailyReleaseService.StartResult;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.pipeline.ExecType;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The daily release use case against the real test schema (P5-A design §5, §7 items 1-4, 7):
 * creation without a release order and without the unit lock, the unchanged 变更项, parallel
 * starts, the validation order, the audit row and the two read paths.
 *
 * <p>The pipeline is not started here (the test profile sets {@code release.pipeline.auto-start:
 * false}), so every assertion is about what the start <em>created</em>; the node behaviour is
 * covered by {@link DailyDeployNodeHandlerTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyReleaseServiceTest {

    private static final String OWNER = "01388007";
    private static final String OTHER = "01388009";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";
    private static final String BUILD_PLAN = "P-BUILD-DAILY";
    private static final String DAILY_PLAN = "P-DAILY-DEPLOY";
    private static final String TEST_PLAN = "P-TEST-DEPLOY";
    private static final String PROD_PLAN = "P-PROD-DEPLOY";
    private static final String DAILY_DE_ONE = "daily-one";
    private static final String DAILY_DE_TWO = "daily-two";
    private static final String TEST_DE = "daily-test";
    private static final String PROD_DE = "daily-prod";

    @Autowired ItemAppService itemService;
    @Autowired DailyReleaseService dailyService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineRepository repository;
    @Autowired JdbcTemplate jdbc;
    @MockBean GitCommitProbe commitProbe;

    private long unitId;
    private long itemId;
    private long dailyDeOne;
    private long dailyDeTwo;

    @BeforeEach
    void setUp() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_DAILY));
        clearSchema();

        givenUser(OWNER);
        givenUser(OTHER);
        long subsystemId = givenSubsystem("SCC-DAILY-SVC");
        long taskId = givenTask(subsystemId, "202611-DAILY-S");
        unitId = givenUnit(subsystemId, "daily-svc-api");
        givenBinding(unitId, "BUILD", BUILD_PLAN);
        givenBinding(unitId, "DAILY_DEPLOY", DAILY_PLAN);
        givenBinding(unitId, "TEST_DEPLOY", TEST_PLAN);
        givenBinding(unitId, "PROD_DEPLOY", PROD_PLAN);
        dailyDeOne = givenDe(unitId, "DAILY", DAILY_DE_ONE, 0);
        dailyDeTwo = givenDe(unitId, "DAILY", DAILY_DE_TWO, 2);
        givenDe(unitId, "TEST", TEST_DE, 0);
        givenDe(unitId, "PROD", PROD_DE, 1);
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "日常发布",
            PLAN_DATE));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ the created instance

    @Test
    void start_creates_a_daily_instance_with_no_order_no_lock_and_an_unchanged_item() {
        String statusBefore = itemStatus();
        StartResult result = dailyService.start(itemId);

        assertThat(result.pipelineNo()).startsWith("PL-");
        assertThat(result.instanceId()).isPositive();

        PipelineInstance instance = repository.loadInstance(result.instanceId());
        assertThat(instance.execType()).isEqualTo(ExecType.DAILY);
        assertThat(instance.orderId()).as("a daily release creates no release order").isZero();
        assertThat(instance.itemId()).isEqualTo(itemId);
        assertThat(instance.unitId()).isEqualTo(unitId);
        assertThat(instance.requestedBy()).isEqualTo(OWNER);
        assertThat(instance.status().name()).isEqualTo("PENDING");
        assertThat(instance.currentNode().name()).isEqualTo("BUILD");
        assertThat(instance.triggerType().name()).isEqualTo("INITIAL");
        assertThat(instance.nodes()).hasSize(2);
        assertThat(instance.node(NodeType.BUILD).tasks())
            .extracting(task -> task.taskType().name())
            .containsExactly("CODE_CHECK", "PKG_BUILD", "IMG_BUILD");
        assertThat(instance.node(NodeType.DAILY_DEPLOY).tasks())
            .extracting(task -> task.targetId()).containsExactly(dailyDeOne, dailyDeTwo);

        assertThat(count("release_order")).as("no 发布单 row is written").isZero();
        assertThat(count("deploy_unit_lock")).as("no unit lock is taken").isZero();
        assertThat(itemStatus()).as("a daily release never changes the 变更项 status")
            .isEqualTo(statusBefore);
        assertThat(repository.contextOf(result.instanceId()).unitId())
            .as("the orchestrator sees a daily instance as lease-free")
            .isZero();
    }

    @Test
    void daily_contexts_resolve_the_item_without_a_release_order() {
        StartResult result = dailyService.start(itemId);

        PipelineRepository.BuildContext build = repository.dailyBuildContextOf(result.instanceId());
        assertThat(build.unitId()).isEqualTo(unitId);
        assertThat(build.planId()).isEqualTo(BUILD_PLAN);
        assertThat(build.branch()).isEqualTo(BRANCH);
        assertThat(build.commitSha()).as("nothing was built yet").isNull();

        PipelineRepository.DeployContext deploy =
            repository.dailyDeployContextOf(result.instanceId());
        assertThat(deploy.unitId()).isEqualTo(unitId);
        assertThat(deploy.planId()).as("the DAILY_DEPLOY binding is the default deploy plan")
            .isEqualTo(DAILY_PLAN);
        assertThat(deploy.publishNo()).as("a daily release has no publish number").isNull();
        assertThat(deploy.targets()).extracting(PipelineRepository.DeployTarget::deId)
            .containsExactly(dailyDeOne, dailyDeTwo);
        assertThat(deploy.targets()).extracting(PipelineRepository.DeployTarget::envKind)
            .containsOnly("DAILY");

        // The online path cannot serve a daily instance: it resolves through the release order.
        PipelineRepository.BuildContext onlinePath =
            repository.buildContextOf(result.instanceId());
        assertThat(onlinePath.unitId()).isZero();
        assertThat(onlinePath.planId()).isNull();
    }

    @Test
    void daily_deploy_context_falls_back_to_the_test_binding_and_test_des() {
        long testOnlyUnit = givenUnit(givenSubsystem("SCC-DAILY-FB"), "daily-fallback-api");
        givenBinding(testOnlyUnit, "BUILD", BUILD_PLAN);
        // Only the TEST_DEPLOY binding and a TEST DE: both C24 fallbacks apply.
        givenBinding(testOnlyUnit, "TEST_DEPLOY", TEST_PLAN);
        long fallbackDe = givenDe(testOnlyUnit, "TEST", TEST_DE, 0);
        long fallbackTask = givenTask(subsystemOf(testOnlyUnit), "202611-DAILY-FB");
        long fallbackItem = itemService.create(fallbackTask,
            new CreateItemCmd(testOnlyUnit, BRANCH, "日常回退", PLAN_DATE));

        StartResult result = dailyService.start(fallbackItem);

        PipelineRepository.DeployContext deploy =
            repository.dailyDeployContextOf(result.instanceId());
        assertThat(deploy.planId()).isEqualTo(TEST_PLAN);
        assertThat(deploy.targets()).extracting(PipelineRepository.DeployTarget::deId)
            .containsExactly(fallbackDe);
        assertThat(deploy.targets()).extracting(PipelineRepository.DeployTarget::envKind)
            .containsOnly("TEST");
        PipelineInstance instance = repository.loadInstance(result.instanceId());
        assertThat(instance.node(NodeType.DAILY_DEPLOY).tasks())
            .extracting(task -> task.targetId()).containsExactly(fallbackDe);
    }

    // ------------------------------------------------------------------ parallel and isolation

    @Test
    void the_same_item_can_start_two_daily_releases_in_parallel() {
        StartResult first = dailyService.start(itemId);
        StartResult second = dailyService.start(itemId);

        assertThat(first.pipelineNo()).isNotEqualTo(second.pipelineNo());
        assertThat(first.instanceId()).isNotEqualTo(second.instanceId());
        assertThat(count("pipeline_instance")).isEqualTo(2);
        assertThat(count("deploy_unit_lock")).isZero();

        PipelineInstance a = repository.loadInstance(first.instanceId());
        PipelineInstance b = repository.loadInstance(second.instanceId());
        assertThat(a.status().name()).isEqualTo("PENDING");
        assertThat(b.status().name()).isEqualTo("PENDING");
        assertThat(itemStatus()).isEqualTo("PENDING");
    }

    @Test
    void a_running_online_release_does_not_block_a_daily_start_and_keeps_its_lock() {
        PublishAppService.PublishResult published = publishService.publish(itemId);
        long onlineInstanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        String itemStatusAfterPublish = itemStatus();

        StartResult daily = dailyService.start(itemId);

        assertThat(daily.pipelineNo()).isNotEqualTo(published.pipelineNo());
        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?",
            Long.class, unitId))
            .as("the online release still owns the unit lock")
            .isEqualTo(onlineInstanceId);
        assertThat(itemStatus())
            .as("the daily start did not move the item the online publish moved")
            .isEqualTo(itemStatusAfterPublish);
        assertThat(count("deploy_unit_lock")).isEqualTo(1);
    }

    @Test
    void a_daily_instance_never_appears_as_the_latest_online_instance_of_the_item() {
        dailyService.start(itemId);

        assertThat(repository.latestInstanceOf(itemId))
            .as("a daily instance has no order, so it cannot be the newest online execution")
            .isNull();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void a_library_item_is_refused() {
        long libraryItem = givenLibraryItem(itemId);

        assertThatThrownBy(() -> dailyService.start(libraryItem))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.STATE_NOT_ALLOWED))
            .hasMessageContaining("二方库");
        assertThat(count("pipeline_instance")).isZero();
    }

    @Test
    void an_archived_task_is_refused() {
        long taskId = taskIdOf(itemId);
        jdbc.update("UPDATE release_task SET status = 'ARCHIVED' WHERE id = ?", taskId);

        assertThatThrownBy(() -> dailyService.start(itemId))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.STATE_NOT_ALLOWED))
            .hasMessageContaining("任务已归档");
        assertThat(count("pipeline_instance")).isZero();
    }

    @Test
    void a_unit_without_a_daily_or_test_de_is_refused() {
        long noDeUnit = givenUnit(givenSubsystem("SCC-DAILY-NODE"), "daily-node-api");
        givenBinding(noDeUnit, "BUILD", BUILD_PLAN);
        givenBinding(noDeUnit, "TEST_DEPLOY", TEST_PLAN);
        long noDeTask = givenTask(subsystemOf(noDeUnit), "202611-DAILY-NODE");
        long noDeItem = itemService.create(noDeTask,
            new CreateItemCmd(noDeUnit, BRANCH, "无环境", PLAN_DATE));

        assertThatThrownBy(() -> dailyService.start(noDeItem))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.STATE_NOT_ALLOWED))
            .hasMessageContaining("没有日常/测试环境 DE");
        assertThat(count("pipeline_instance")).isZero();
    }

    @Test
    void a_missing_deploy_binding_is_refused_before_anything_is_written() {
        long noPlanUnit = givenUnit(givenSubsystem("SCC-DAILY-NOPLAN"), "daily-noplan-api");
        givenBinding(noPlanUnit, "BUILD", BUILD_PLAN);
        long noPlanDe = givenDe(noPlanUnit, "TEST", TEST_DE, 0);
        long noPlanTask = givenTask(subsystemOf(noPlanUnit), "202611-DAILY-NOPLAN");
        long noPlanItem = itemService.create(noPlanTask,
            new CreateItemCmd(noPlanUnit, BRANCH, "无部署任务", PLAN_DATE));

        assertThatThrownBy(() -> dailyService.start(noPlanItem))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.STATE_NOT_ALLOWED))
            .hasMessageContaining("日常部署");
        assertThat(count("pipeline_instance")).isZero();
        assertThat(noPlanDe).isPositive();
    }

    @Test
    void start_requires_the_release_daily_permission() {
        // A caller with roles but no permission points: authenticated, refused by the use case.
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of());

        assertThatThrownBy(() -> dailyService.start(itemId))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.FORBIDDEN))
            .hasMessageContaining(Permissions.RELEASE_DAILY);
        assertThat(count("pipeline_instance")).isZero();
    }

    @Test
    void start_is_scoped_to_the_items_unit() {
        TestUserContext.scopedWithPerms(OTHER, Set.of(Permissions.RELEASE_DAILY),
            unitId + 1_000L);

        assertThatThrownBy(() -> dailyService.start(itemId))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.FORBIDDEN))
            .hasMessageContaining("部署单元");
        assertThat(count("pipeline_instance")).isZero();
    }

    @Test
    void an_unknown_item_is_a_business_40000() {
        assertThatThrownBy(() -> dailyService.start(9_999_999L))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.PARAM_INVALID));
    }

    // ------------------------------------------------------------------ reads

    @Test
    void the_records_page_is_newest_first_and_paged() {
        StartResult older = dailyService.start(itemId);
        StartResult newer = dailyService.start(itemId);

        DailyPage firstPage = dailyService.page(itemId, 1, 1);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.records()).hasSize(1);
        assertThat(firstPage.records().get(0).pipelineNo()).isEqualTo(newer.pipelineNo());

        DailyPage secondPage = dailyService.page(itemId, 2, 1);
        assertThat(secondPage.records()).hasSize(1);
        assertThat(secondPage.records().get(0).pipelineNo()).isEqualTo(older.pipelineNo());

        DailyRecord row = secondPage.records().get(0);
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.requestedBy()).isEqualTo(OWNER);
        assertThat(row.branch()).isEqualTo(BRANCH);
        assertThat(row.commitSha()).isNull();
        assertThat(row.createdAt()).isNotNull();
        assertThat(row.startAt()).as("the pipeline was not started in this profile").isNull();

        assertThatThrownBy(() -> dailyService.page(itemId, 0, 10))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> dailyService.page(itemId, 1, 0))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> dailyService.page(itemId, 1, 101))
            .isInstanceOf(BizException.class);
    }

    @Test
    void the_detail_returns_the_two_nodes_with_their_tasks_and_log_links() {
        StartResult started = dailyService.start(itemId);

        DailyDetail detail = dailyService.detail(started.pipelineNo());

        assertThat(detail.pipelineNo()).isEqualTo(started.pipelineNo());
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.requestedBy()).isEqualTo(OWNER);
        assertThat(detail.branch()).isEqualTo(BRANCH);
        assertThat(detail.nodes()).hasSize(2);
        assertThat(detail.nodes().get(0).nodeType()).isEqualTo("BUILD");
        assertThat(detail.nodes().get(0).tasks()).hasSize(3);
        assertThat(detail.nodes().get(1).nodeType()).isEqualTo("DAILY_DEPLOY");
        assertThat(detail.nodes().get(1).tasks()).hasSize(2);
        assertThat(detail.nodes().get(1).tasks()).extracting(DailyTask::taskType)
            .containsOnly("DEPLOY");
        assertThat(detail.nodes().get(1).tasks()).extracting(DailyTask::externalUrl)
            .as("no deploy was triggered yet").containsOnlyNulls();

        assertThatThrownBy(() -> dailyService.detail("PL-UNKNOWN"))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.PARAM_INVALID));
        assertThatThrownBy(() -> dailyService.detail("  "))
            .isInstanceOf(BizException.class);
    }

    @Test
    void an_online_pipeline_number_is_not_a_daily_record() {
        PublishAppService.PublishResult published = publishService.publish(itemId);

        assertThatThrownBy(() -> dailyService.detail(published.pipelineNo()))
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError())
                .isEqualTo(BizError.PARAM_INVALID))
            .hasMessageContaining("日常发布记录不存在");
    }

    // ------------------------------------------------------------------ audit

    @Test
    void start_writes_exactly_one_daily_start_row_on_the_item() {
        StartResult started = dailyService.start(itemId);

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
            "SELECT actor, action, object_type, object_id, scope_unit_id, after_json"
                + " FROM audit_log WHERE action = 'DAILY_START'");
        assertThat(rows).hasSize(1);
        java.util.Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor")).isEqualTo(OWNER);
        assertThat(row.get("object_type")).as("DAILY_START defaults to ORDER; the daily start"
            + " names the ITEM explicitly").isEqualTo("ITEM");
        assertThat(String.valueOf(row.get("object_id"))).isEqualTo(String.valueOf(itemId));
        assertThat(((Number) row.get("scope_unit_id")).longValue()).isEqualTo(unitId);
        assertThat(String.valueOf(row.get("after_json")))
            .contains(started.pipelineNo()).contains("\"instanceId\"");

        dailyService.start(itemId);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'DAILY_START'", Integer.class))
            .as("one row per start, even for the same item (occurrences, not state)")
            .isEqualTo(2);
    }

    @Test
    void a_failing_audit_write_never_blocks_the_start() {
        // The real AuditService is used; the audit table is made unwritable, so its REQUIRES_NEW
        // insert throws. DailyReleaseService calls it bare, exactly like every other trigger, and
        // AuditService swallows the failure — the daily start must still succeed.
        jdbc.execute("RENAME TABLE audit_log TO audit_log_broken");
        try {
            StartResult started = dailyService.start(itemId);

            assertThat(started.instanceId()).isPositive();
            assertThat(repository.loadInstance(started.instanceId())).isNotNull();
            assertThat(count("pipeline_instance")).isEqualTo(1);
        } finally {
            jdbc.execute("RENAME TABLE audit_log_broken TO audit_log");
        }
    }

    // ------------------------------------------------------------------ row read-back

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private long taskIdOf(long item) {
        return jdbc.queryForObject("SELECT task_id FROM release_item WHERE id = ?", Long.class,
            item);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ fixtures

    private void clearSchema() {
        for (String table : List.of("audit_log", "notification", "notification_setting",
            "coding_conn", "deploy_unit_lock", "rollback_record", "pipeline_item_failure",
            "node_task", "pipeline_node", "pipeline_instance", "env_de", "coding_task_binding",
            "release_order", "release_item", "release_task_member", "release_task", "deploy_unit",
            "subsystem", "user_scope", "user_role", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long subsystemOf(long unit) {
        return jdbc.queryForObject("SELECT subsystem_id FROM deploy_unit WHERE id = ?",
            Long.class, unit);
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/daily', NOW(3), NOW(3))",
            subsystem, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, codingTaskId);
    }

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }

    /** A LIB 变更项 is refused before anything is created; the row is inserted directly. */
    private long givenLibraryItem(long referenceItem) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, module_path, version, plan_release_date, status, gate_status,"
                + " test_status, row_version, created_at, updated_at)"
                + " VALUES (?, 'CRID-LIB-1', 'LIB', ?, ?, 'release/lib', NULL, '二方库', ?,"
                + " 'PENDING', 'CHECK_RUNNING', 'TEST_PENDING', 0, NOW(3), NOW(3))",
            taskIdOf(referenceItem), unitId, BUILD_PLAN, PLAN_DATE);
        return jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CRID-LIB-1'",
            Long.class);
    }
}